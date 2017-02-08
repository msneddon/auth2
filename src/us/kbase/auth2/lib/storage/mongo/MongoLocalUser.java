package us.kbase.auth2.lib.storage.mongo;

import java.util.Collections;
import java.util.Date;
import java.util.Set;

import org.bson.types.ObjectId;

import us.kbase.auth2.lib.DisplayName;
import us.kbase.auth2.lib.EmailAddress;
import us.kbase.auth2.lib.LocalUser;
import us.kbase.auth2.lib.Role;
import us.kbase.auth2.lib.UserDisabledState;
import us.kbase.auth2.lib.UserName;
import us.kbase.auth2.lib.storage.exceptions.AuthStorageException;

/** A MongoDB specific implementation of a LocalUser.
 * 
 * @see LocalUser
 * 
 * @author gaprice@lbl.gov
 *
 */
public class MongoLocalUser extends LocalUser {

	//TODO TESTS
	
	private final MongoStorage storage;
	private Set<ObjectId> customRoles;
	private Set<String> memoizedCustomRoles;
	
	MongoLocalUser(
			final UserName userName,
			final EmailAddress email,
			final DisplayName displayName,
			final Set<Role> roles,
			final Set<ObjectId> customRoles,
			final Date created,
			final Date lastLogin,
			final UserDisabledState disabledState,
			final byte[] passwordHash,
			final byte[] salt,
			final boolean forceReset,
			final Date lastReset,
			final MongoStorage storage) {
		super(userName, email, displayName, roles, created, lastLogin, disabledState,
				passwordHash, salt, forceReset, lastReset);
		this.customRoles = Collections.unmodifiableSet(customRoles);
		if (customRoles.isEmpty()) {
			memoizedCustomRoles = Collections.emptySet();
		}
		this.storage = storage;
	}

	@Override
	public Set<String> getCustomRoles() throws AuthStorageException {
		if (memoizedCustomRoles == null) {
			memoizedCustomRoles = Collections.unmodifiableSet(
					storage.getCustomRoles(getUserName(), customRoles));
		}
		return memoizedCustomRoles;
	}
}
