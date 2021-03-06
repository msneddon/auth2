package us.kbase.test.auth2.lib.storage.mongo;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import static us.kbase.test.auth2.TestCommon.set;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.Test;

import us.kbase.auth2.lib.AuthUser;
import us.kbase.auth2.lib.DisplayName;
import us.kbase.auth2.lib.EmailAddress;
import us.kbase.auth2.lib.NewLocalUser;
import us.kbase.auth2.lib.NewUser;
import us.kbase.auth2.lib.UserName;
import us.kbase.auth2.lib.exceptions.LinkFailedException;
import us.kbase.auth2.lib.exceptions.NoSuchUserException;
import us.kbase.auth2.lib.exceptions.UnLinkFailedException;
import us.kbase.auth2.lib.identity.RemoteIdentityDetails;
import us.kbase.auth2.lib.identity.RemoteIdentityID;
import us.kbase.auth2.lib.identity.RemoteIdentityWithLocalID;
import us.kbase.auth2.lib.storage.mongo.MongoStorage;
import us.kbase.test.auth2.TestCommon;

public class MongoStorageLinkTest extends MongoStorageTester {

	private static final Instant NOW = Instant.now();
	
	private static final RemoteIdentityWithLocalID REMOTE1 = new RemoteIdentityWithLocalID(
			UUID.fromString("ec8a91d3-5923-4639-8d12-0891c56715d8"),
			new RemoteIdentityID("prov", "bar1"),
			new RemoteIdentityDetails("user1", "full1", "email1"));
	
	private static final RemoteIdentityWithLocalID REMOTE2 = new RemoteIdentityWithLocalID(
			UUID.fromString("ec8a91d3-5923-4639-8d12-0891d56715d8"),
			new RemoteIdentityID("prov", "bar2"),
			new RemoteIdentityDetails("user2", "full2", "email2"));
	
	private static final RemoteIdentityWithLocalID REMOTE3 = new RemoteIdentityWithLocalID(
			UUID.fromString("ec8a91d3-5923-4639-8d12-0891e56715d8"),
			new RemoteIdentityID("prov", "bar3"),
			new RemoteIdentityDetails("user3", "full3", "email3"));

	@Test
	public void link() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(REMOTE2, REMOTE1)));
	}
	
	@Test
	public void unlink() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		storage.unlink(new UserName("foo"), REMOTE1.getID());
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(REMOTE2)));
	}
	
	@Test
	public void linkNoop() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		final RemoteIdentityWithLocalID ri = new RemoteIdentityWithLocalID(
				UUID.randomUUID(),
				new RemoteIdentityID("prov", "bar2"),
				new RemoteIdentityDetails("user2", "full2", "email2"));
		storage.link(new UserName("foo"), ri); // noop
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(REMOTE2, REMOTE1)));
	}
	
	@Test
	public void linkAndUpdateIdentity() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		final RemoteIdentityWithLocalID ri = new RemoteIdentityWithLocalID(
				UUID.randomUUID(),
				new RemoteIdentityID("prov", "bar2"),
				new RemoteIdentityDetails("user10", "full10", "email10"));
		storage.link(new UserName("foo"), ri);
		
		final RemoteIdentityWithLocalID expected = new RemoteIdentityWithLocalID(
				REMOTE2.getID(),
				new RemoteIdentityID("prov", "bar2"),
				new RemoteIdentityDetails("user10", "full10", "email10"));
		
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(expected, REMOTE1)));
	}
	
	@Test
	public void linkReflectionPass() throws Exception {
		final Method m = MongoStorage.class.getDeclaredMethod(
				"addIdentity", AuthUser.class, RemoteIdentityWithLocalID.class);
		m.setAccessible(true);
		
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		final AuthUser au = storage.getUser(new UserName("foo"));
		final boolean p = (boolean) m.invoke(storage, au, REMOTE2);
		assertThat("expected successful link", p, is(true));
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(REMOTE1, REMOTE2)));
	}
	
	@Test
	public void linkReflectionAddIDFail() throws Exception {
		/* This tests the case where a user's identities are changed between pulling the user from
		 * the db and running the link.
		 */
		final Method m = MongoStorage.class.getDeclaredMethod(
				"addIdentity", AuthUser.class, RemoteIdentityWithLocalID.class);
		m.setAccessible(true);
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		final AuthUser au = storage.getUser(new UserName("foo"));
		storage.link(new UserName("foo"), REMOTE3);
		final boolean p = (boolean) m.invoke(storage, au, REMOTE2);
		assertThat("expected failed link", p, is(false));
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(REMOTE1, REMOTE3)));
	}
	
	@Test
	public void linkReflectionRemoveIDFail() throws Exception {
		/* This tests the case where a user's identities are changed between pulling the user from
		 * the db and running the link.
		 */
		final Method m = MongoStorage.class.getDeclaredMethod(
				"addIdentity", AuthUser.class, RemoteIdentityWithLocalID.class);
		m.setAccessible(true);
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		final AuthUser au = storage.getUser(new UserName("foo"));
		storage.unlink(new UserName("foo"), REMOTE1.getID());
		final boolean p = (boolean) m.invoke(storage, au, REMOTE3);
		assertThat("expected failed link", p, is(false));
		assertThat("incorrect identities", storage.getUser(new UserName("foo")).getIdentities(),
				is(set(REMOTE2)));
	}
	
	@Test
	public void linkFailNulls() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		failLink(null, REMOTE1, new NullPointerException("userName"));
		failLink(new UserName("foo"), null, new NullPointerException("remoteID"));
	}
	
	@Test
	public void linkFailNoUser() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		failLink(new UserName("foo1"), REMOTE2, new NoSuchUserException("foo1"));
	}
	
	@Test
	public void linkFailLocalUser() throws Exception {
		final byte[] pwd = "foobarbaz2".getBytes(StandardCharsets.UTF_8);
		final byte[] salt = "whee".getBytes(StandardCharsets.UTF_8);
		final NewLocalUser nlu = new NewLocalUser(
				new UserName("local"), new EmailAddress("e@g.com"), new DisplayName("bar"),
				NOW, pwd, salt, false);
				
		storage.createLocalUser(nlu);
		failLink(new UserName("local"), REMOTE2,
				new LinkFailedException("Cannot link identities to a local user"));
	}
	
	@Test
	public void linkFailAlreadyLinked() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE2, NOW, null));
		storage.createUser(new NewUser(new UserName("foo2"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		
		final RemoteIdentityWithLocalID ri = new RemoteIdentityWithLocalID(
				UUID.randomUUID(),
				new RemoteIdentityID("prov", "bar2"),
				new RemoteIdentityDetails("user10", "full10", "email10"));
		failLink(new UserName("foo2"), ri,
				new LinkFailedException("Provider identity is already linked"));
	}
	
	private void failLink(
			final UserName name,
			final RemoteIdentityWithLocalID id,
			final Exception e) {
		try {
			storage.link(name, id);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, e);
		}
	}
	
	@Test
	public void unlinkFailNulls() throws Exception {
		failUnlink(null, UUID.randomUUID(), new NullPointerException("userName"));
		failUnlink(new UserName("foo"), null, new NullPointerException("id"));
	}
	
	@Test
	public void unlinkFailNoUser() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		failUnlink(new UserName("foo1"), REMOTE1.getID(), new NoSuchUserException("foo1"));
	}
	
	@Test
	public void unlinkFailLocalUser() throws Exception {
		final byte[] pwd = "foobarbaz2".getBytes(StandardCharsets.UTF_8);
		final byte[] salt = "whee".getBytes(StandardCharsets.UTF_8);
		final NewLocalUser nlu = new NewLocalUser(
				new UserName("local"), new EmailAddress("e@g.com"), new DisplayName("bar"),
				NOW, pwd, salt, false);
				
		storage.createLocalUser(nlu);
		failUnlink(new UserName("local"), UUID.randomUUID(),
				new UnLinkFailedException("Local users have no identities"));
	}
	
	@Test
	public void unlinkFailOneIdentity() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		failUnlink(new UserName("foo"), REMOTE1.getID(),
				new UnLinkFailedException("The user has only one associated identity"));
	}
	
	@Test
	public void unlinkFailNoSuchIdentity() throws Exception {
		storage.createUser(new NewUser(new UserName("foo"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE1, NOW, null));
		storage.link(new UserName("foo"), REMOTE2);
		storage.createUser(new NewUser(new UserName("foo1"), new EmailAddress("f@g.com"),
				new DisplayName("bar"), REMOTE3, NOW, null));
		failUnlink(new UserName("foo"), REMOTE3.getID(),
				new UnLinkFailedException("The user is not linked to the provided identity"));
	}
	
	private void failUnlink(final UserName name, final UUID id, final Exception e) {
		try {
			storage.unlink(name, id);
			fail("exception expected");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, e);
		}
	}
}
