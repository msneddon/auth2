package us.kbase.auth2.service.ui;

import static us.kbase.auth2.service.ui.UIUtils.getMaxCookieAge;
import static us.kbase.auth2.service.ui.UIUtils.getTokenFromCookie;
import static us.kbase.auth2.service.ui.UIUtils.relativize;
import static us.kbase.auth2.service.ui.UIUtils.upperCase;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.NoSuchProviderException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.CookieParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.glassfish.jersey.server.mvc.Template;
import org.glassfish.jersey.server.mvc.Viewable;

import us.kbase.auth2.lib.AuthUser;
import us.kbase.auth2.lib.Authentication;
import us.kbase.auth2.lib.LinkIdentities;
import us.kbase.auth2.lib.LinkToken;
import us.kbase.auth2.lib.exceptions.AuthenticationException;
import us.kbase.auth2.lib.exceptions.DisabledUserException;
import us.kbase.auth2.lib.exceptions.ErrorType;
import us.kbase.auth2.lib.exceptions.ExternalConfigMappingException;
import us.kbase.auth2.lib.exceptions.InvalidTokenException;
import us.kbase.auth2.lib.exceptions.LinkFailedException;
import us.kbase.auth2.lib.exceptions.MissingParameterException;
import us.kbase.auth2.lib.exceptions.NoSuchIdentityProviderException;
import us.kbase.auth2.lib.exceptions.NoTokenProvidedException;
import us.kbase.auth2.lib.identity.RemoteIdentityWithID;
import us.kbase.auth2.lib.storage.exceptions.AuthStorageException;
import us.kbase.auth2.lib.token.IncomingToken;
import us.kbase.auth2.lib.token.TemporaryToken;
import us.kbase.auth2.service.AuthAPIStaticConfig;
import us.kbase.auth2.service.AuthExternalConfig.AuthExternalConfigMapper;

@Path(UIPaths.LINK_ROOT)
public class Link {

	//TODO JAVADOC
	//TODO TEST
	//TODO CODE can probably share some code with /login
	
	//TODO NOW CODE UI copy flow from login
	
	private static final String LINK_STATE_COOKIE = "linkstatevar";
	private static final String IN_PROCESS_LINK_COOKIE = "in-process-link-token";

	@Inject
	private Authentication auth;
	
	@Inject
	private AuthAPIStaticConfig cfg;
	
	@GET
	public Response linkStartDisplay(
			@Context final HttpHeaders headers,
			@QueryParam("provider") final String provider,
			@Context UriInfo uriInfo)
			throws NoSuchIdentityProviderException, NoTokenProvidedException,
			InvalidTokenException, AuthStorageException, DisabledUserException {

		final IncomingToken incToken = getTokenFromCookie(headers, cfg.getTokenCookieName());
		
		if (provider != null && !provider.trim().isEmpty()) {
			final String state = auth.getBareToken();
			final URI target = toURI(
					auth.getIdentityProviderURL(provider, state, true));
			return Response.seeOther(target)
					.cookie(getStateCookie(state))
					.build();
		} else {
			final AuthUser u = auth.getUser(incToken);
			final Map<String, Object> ret = new HashMap<>();
			ret.put("user", u.getUserName().getName());
			ret.put("local", u.isLocal());
			final List<Map<String, String>> provs = new LinkedList<>();
			ret.put("providers", provs);
			for (final String prov: auth.getIdentityProviders()) {
				final Map<String, String> rep = new HashMap<>();
				rep.put("name", prov);
				final URI i = auth.getIdentityProviderImageURI(prov);
				if (i.isAbsolute()) {
					rep.put("img", i.toString());
				} else {
					rep.put("img", relativize(uriInfo, i));
				}
				provs.add(rep);
			}
			ret.put("hasprov", !provs.isEmpty());
			ret.put("urlpre", "?provider=");
			return Response.ok().entity(new Viewable("/linkstart", ret))
					.build();
		}
	}
	
	private NewCookie getStateCookie(final String state) {
		return new NewCookie(new Cookie(LINK_STATE_COOKIE,
				state == null ? "no state" : state, UIPaths.LINK_ROOT_COMPLETE, null),
				"linkstate", state == null ? 0 : 30 * 60, UIConstants.SECURE_COOKIES);
	}
	
	@GET
	@Path(UIPaths.LINK_COMPLETE_PROVIDER)
	public Response link(
			@Context final HttpHeaders headers,
			@PathParam("provider") String provider,
			@CookieParam(LINK_STATE_COOKIE) final String state,
			@Context final UriInfo uriInfo)
			throws MissingParameterException, AuthenticationException,
			NoSuchProviderException, AuthStorageException,
			NoTokenProvidedException, LinkFailedException, DisabledUserException {
		//TODO INPUT handle error in params (provider, state)
		provider = upperCase(provider);
		final MultivaluedMap<String, String> qps = uriInfo.getQueryParameters();
		//TODO ERRHANDLE handle returned OAuth error code in queryparams
		final String authcode = qps.getFirst("code"); //may need to be configurable
		final String retstate = qps.getFirst("state"); //may need to be configurable
		if (state == null || state.trim().isEmpty()) {
			throw new MissingParameterException("Couldn't retrieve state value from cookie");
		}
		if (!state.equals(retstate)) {
			throw new AuthenticationException(ErrorType.AUTHENTICATION_FAILED,
					"State values do not match, this may be a CXRF attack");
		}
		final LinkToken lt = auth.link(getTokenFromCookie(headers, cfg.getTokenCookieName()),
				provider, authcode);
		final Response r;
		// always redirect so the authcode doesn't remain in the title bar
		// note nginx will rewrite the redirect appropriately so absolute
		// redirects are ok
		if (lt.isLinked()) {
			r = Response.seeOther(getPostLinkRedirectURI(UIPaths.ME_ROOT))
					.cookie(getStateCookie(null)).build();
		} else {
			r = Response.seeOther(getCompleteLinkRedirectURI(UIPaths.LINK_ROOT_CHOICE)).cookie(
					getLinkInProcessCookie(lt.getTemporaryToken()))
					.cookie(getStateCookie(null))
					.build();
		}
		return r;
	}
	
	// the two methods below are very similar and there's another similar method in Login
	private URI getCompleteLinkRedirectURI(final String deflt) throws AuthStorageException {
		final URL url;
		try {
			url = auth.getExternalConfig(new AuthExternalConfigMapper())
					.getCompleteLinkRedirect();
		} catch (ExternalConfigMappingException e) {
			throw new RuntimeException("Dude, like, what just happened?", e);
		}
		if (url == null) {
			return toURI(deflt);
		}
		try {
			return url.toURI();
		} catch (URISyntaxException e) {
			throw new RuntimeException("this should be impossible" , e);
		}
	}
	
	private URI getPostLinkRedirectURI(final String deflt) throws AuthStorageException {
		final URL url;
		try {
			url = auth.getExternalConfig(new AuthExternalConfigMapper())
					.getPostLinkRedirect();
		} catch (ExternalConfigMappingException e) {
			throw new RuntimeException("Dude, like, what just happened?", e);
		}
		if (url == null) {
			return toURI(deflt);
		}
		try {
			return url.toURI();
		} catch (URISyntaxException e) {
			throw new RuntimeException("this should be impossible" , e);
		}
	}
	
	private NewCookie getLinkInProcessCookie(final TemporaryToken token) {
		return new NewCookie(new Cookie(IN_PROCESS_LINK_COOKIE,
				token == null ? "no token" : token.getToken(), UIPaths.LINK_ROOT, null),
				"linktoken", token == null ? 0 : getMaxCookieAge(token, false),
				UIConstants.SECURE_COOKIES);
	}
	
	@GET
	@Path(UIPaths.LINK_CHOICE)
	@Template(name = "/linkchoice")
	@Produces(MediaType.TEXT_HTML)
	public Map<String, Object> linkChoiceHTML(
			@Context final HttpHeaders headers,
			@CookieParam(IN_PROCESS_LINK_COOKIE) final String linktoken,
			@Context final UriInfo uriInfo)
			throws NoTokenProvidedException, AuthStorageException,
			InvalidTokenException, LinkFailedException, DisabledUserException {
		return linkChoice(headers, linktoken, uriInfo);
	}
	
	// trying to combine JSON and HTML doesn't work - @Template = always HTML regardless of Accept:
	@GET
	@Path(UIPaths.LINK_CHOICE)
	@Produces(MediaType.APPLICATION_JSON)
	public Map<String, Object> linkChoiceJSON(
			@Context final HttpHeaders headers,
			@CookieParam(IN_PROCESS_LINK_COOKIE) final String linktoken,
			@Context final UriInfo uriInfo)
			throws NoTokenProvidedException, AuthStorageException,
			InvalidTokenException, LinkFailedException, DisabledUserException {
		return linkChoice(headers, linktoken, uriInfo);
	}

	private Map<String, Object> linkChoice(
			final HttpHeaders headers,
			final String linktoken,
			final UriInfo uriInfo)
			throws NoTokenProvidedException, InvalidTokenException, AuthStorageException,
			LinkFailedException, DisabledUserException {
		if (linktoken == null || linktoken.trim().isEmpty()) {
			throw new NoTokenProvidedException("Missing " + IN_PROCESS_LINK_COOKIE);
		}
		final LinkIdentities ids = auth.getLinkState(
				getTokenFromCookie(headers, cfg.getTokenCookieName()),
				new IncomingToken(linktoken.trim()));
		/* there's a possibility here that between the redirects the number
		 * of identities that aren't already linked was reduced 1. The
		 * probability is so low that it's not worth special casing it,
		 * especially since the effect is simply that the user only has one
		 * choice for link targets.
		 */ 
		final Map<String, Object> ret = new HashMap<>();
		ret.put("user", ids.getUser().getUserName().getName());
		ret.put("provider", ids.getIdentities()
				.iterator().next().getRemoteID().getProvider());
		final List<Map<String, String>> ris = new LinkedList<>();
		ret.put("ids", ris);
		for (final RemoteIdentityWithID ri: ids.getIdentities()) {
			final Map<String, String> s = new HashMap<>();
			s.put("id", ri.getID().toString());
			s.put("prov_username", ri.getDetails().getUsername());
			ris.add(s);
		}
		ret.put("pickurl", relativize(uriInfo, UIPaths.LINK_ROOT_PICK));
		return ret;
	}
	
	// for dumb HTML pages that use forms
	@POST
	@Path(UIPaths.LINK_PICK)
	public Response pickAccountPOST(
			@Context final HttpHeaders headers,
			@CookieParam(IN_PROCESS_LINK_COOKIE) final String linktoken,
			@FormParam("id") final UUID identityID)
			throws NoTokenProvidedException, AuthenticationException,
			AuthStorageException, LinkFailedException, DisabledUserException {
		
		pickAccount(headers, linktoken, identityID);
		return Response.seeOther(getPostLinkRedirectURI(UIPaths.ME_ROOT))
				.cookie(getLinkInProcessCookie(null)).build();
	}
	
	// for AJAX pages that can decide for themselves where to go next
	@PUT
	@Path(UIPaths.LINK_PICK)
	public Response pickAccountPUT(
			@Context final HttpHeaders headers,
			@CookieParam(IN_PROCESS_LINK_COOKIE) final String linktoken,
			@QueryParam("id") final UUID identityID)
			throws NoTokenProvidedException, AuthenticationException,
			AuthStorageException, LinkFailedException, DisabledUserException {
		
		pickAccount(headers, linktoken, identityID);
		return Response.noContent().cookie(getLinkInProcessCookie(null)).build();
	}

	private void pickAccount(
			final HttpHeaders headers,
			final String linktoken,
			final UUID identityID)
			throws NoTokenProvidedException, AuthStorageException, AuthenticationException,
			LinkFailedException, DisabledUserException {
		if (linktoken == null || linktoken.trim().isEmpty()) {
			throw new NoTokenProvidedException("Missing " + IN_PROCESS_LINK_COOKIE);
		}
		auth.link(getTokenFromCookie(headers, cfg.getTokenCookieName()),
				new IncomingToken(linktoken), identityID);
	}
	
	//Assumes valid URI in URL form
	private URI toURI(final URL loginURL) {
		try {
			return loginURL.toURI();
		} catch (URISyntaxException e) {
			throw new RuntimeException("This should be impossible", e);
		}
	}
	
	//Assumes valid URI in String form
	private URI toURI(final String uri) {
		try {
			return new URI(uri);
		} catch (URISyntaxException e) {
			throw new RuntimeException("This should be impossible", e);
		}
	}
}
