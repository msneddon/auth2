package us.kbase.auth2.lib;

import java.util.Collections;
import java.util.Date;
import java.util.Set;

import us.kbase.auth2.lib.identity.RemoteIdentityWithID;

public class NewUser extends AuthUser {
	
	//TODO JAVADOC
	//TODO TESTS

	public NewUser(
			final UserName userName,
			final EmailAddress email,
			final DisplayName displayName,
			final Set<RemoteIdentityWithID> identities,
			final Date created,
			final Date lastLogin) {
		super(userName, email, displayName, identities, Collections.emptySet(), created,
				lastLogin);
	}

	@Override
	public Set<String> getCustomRoles() {
		return Collections.emptySet();
	}

}
