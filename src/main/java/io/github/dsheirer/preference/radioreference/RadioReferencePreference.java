/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2019 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */

package io.github.dsheirer.preference.radioreference;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;

import java.util.prefs.Preferences;
import java.util.prefs.BackingStoreException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * User preferences for the display of channel decode events
 */
public class RadioReferencePreference extends Preference
{
    public static final int INVALID_ID = -1;
    private Preferences mPreferences = Preferences.userNodeForPackage(RadioReferencePreference.class);
    private static final String STORE_CREDENTIALS = "store.credentials";
    private static final String USER_NAME = "user.name";
    private static final String PASSWORD = "user.authorization";
    private static final String PREFERRED_COUNTRY_ID = "preferred.country";
    private static final String PREFERRED_STATE_ID = "preferred.state";
    private static final String PREFERRED_COUNTY_ID = "preferred.county";
    private static final String BOOKMARKS = "bookmarks";

    private String mUserName;
    private String mPassword;
    private Boolean mStoreCredentials;
    private int mPreferredCountryId = INVALID_ID;
    private int mPreferredStateId = INVALID_ID;
    private int mPreferredCountyId = INVALID_ID;

    /**
     * Constructs an instance.
     * @param updateListener to receive notifications when preferences are updated.
     */
    public RadioReferencePreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        loadSettings();
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.RADIO_REFERENCE;
    }

    private void loadSettings()
    {
        mStoreCredentials = mPreferences.getBoolean(STORE_CREDENTIALS, true);
    }

    /**
     * Clears user credentials and resets username and password to null
     */
    public void removeStoredCredentials()
    {
        setStoreCredentials(false);
        setUserName(null);
        setPassword(null);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if there are any non-null values stored for user name or password
     * @return true if there are values stored
     */
    public boolean hasStoredCredentials()
    {
        return mPreferences.get(USER_NAME, null) != null || mPreferences.get(PASSWORD, null) != null;
    }

    /**
     * User name
     */
    public String getUserName()
    {
        if(mUserName == null)
        {
            mUserName = mPreferences.get(USER_NAME, null);
        }

        return mUserName;
    }

    /**
     * Sets user name
     * @param username to store or null to remove any stored value
     */
    public void setUserName(String username)
    {
        mUserName = username;

        if(mUserName == null)
        {
            mPreferences.remove(USER_NAME);
        }
        else
        {
            mPreferences.put(USER_NAME, mUserName);
        }

        notifyPreferenceUpdated();
    }

    /**
     * Password
     * @return password
     */
    public String getPassword()
    {
        if(mPassword == null)
        {
            mPassword = mPreferences.get(PASSWORD, null);
        }

        return mPassword;
    }

    /**
     * Sets the preferences persisted password.
     * @param password to store or null to remove any stored value
     */
    public void setPassword(String password)
    {
        mPassword = password;

        if(mPassword == null)
        {
            mPreferences.remove(PASSWORD);
        }
        else
        {
            mPreferences.put(PASSWORD, mPassword);
        }

        notifyPreferenceUpdated();
    }

    /**
     * Indicates if the credentials should be persisted in the preferences store.
     *
     * @return true if credentials should be stored.
     */
    public boolean isStoreCredentials()
    {
        if(mStoreCredentials == null)
        {
            mStoreCredentials = mPreferences.getBoolean(STORE_CREDENTIALS, true);
        }

        return mStoreCredentials;
    }

    /**
     * Set the store credentials option
     * @param store true to store credentials
     */
    public void setStoreCredentials(boolean store)
    {
        mStoreCredentials = store;
        mPreferences.putBoolean(STORE_CREDENTIALS, store);
        notifyPreferenceUpdated();
    }

    /**
     * Preferred country to use with the service
     */
    public int getPreferredCountryId()
    {
        if(mPreferredCountryId < 0)
        {
            mPreferredCountryId = mPreferences.getInt(PREFERRED_COUNTRY_ID, INVALID_ID);

        }

        return mPreferredCountryId;
    }

    public void setPreferredCountryId(int countryId)
    {
        mPreferredCountryId = countryId;
        mPreferences.putInt(PREFERRED_COUNTRY_ID, countryId);
        notifyPreferenceUpdated();
    }

    /**
     * Preferred state to use with the service
     */
    public int getPreferredStateId()
    {
        if(mPreferredStateId < 0)
        {
            mPreferredStateId = mPreferences.getInt(PREFERRED_STATE_ID, INVALID_ID);
        }

        return mPreferredStateId;
    }

    public void setPreferredStateId(int state)
    {
        mPreferredStateId = state;
        mPreferences.putInt(PREFERRED_STATE_ID, mPreferredStateId);
        notifyPreferenceUpdated();
    }

    /**
     * Preferred county to use with the service
     */
    public int getPreferredCountyId()
    {
        if(mPreferredCountyId < 0)
        {
            mPreferredCountyId = mPreferences.getInt(PREFERRED_COUNTY_ID, INVALID_ID);
        }

        return mPreferredCountyId;
    }

    public void setPreferredCountyId(int county)
    {
        mPreferredCountyId = county;
        mPreferences.putInt(PREFERRED_COUNTY_ID, mPreferredCountyId);
        notifyPreferenceUpdated();
    }

    public enum BookmarkKind
    {
        TRUNKED_SYSTEM, CONVENTIONAL_AGENCY, TALKGROUP_CATEGORY, CONVENTIONAL_CATEGORY
    }

    public record Bookmark(BookmarkKind kind, int id, int parentId, String ownerKind, String name,
                           String parentName)
    {
        public Bookmark
        {
            if(kind == null || id <= 0 || name == null || name.isBlank() || name.length() > 256 ||
                parentId < 0 || parentName != null && parentName.length() > 256)
            {
                throw new IllegalArgumentException("RadioReference bookmark is invalid");
            }

            ownerKind = ownerKind == null ? "" : ownerKind.strip().toUpperCase(java.util.Locale.ROOT);
            name = name.strip();
            parentName = parentName == null ? "" : parentName.strip();
            boolean category = kind == BookmarkKind.TALKGROUP_CATEGORY ||
                kind == BookmarkKind.CONVENTIONAL_CATEGORY;
            if(category != (parentId > 0) ||
                kind == BookmarkKind.TALKGROUP_CATEGORY && !"TRUNKED_SYSTEM".equals(ownerKind) ||
                (kind == BookmarkKind.CONVENTIONAL_AGENCY || kind == BookmarkKind.CONVENTIONAL_CATEGORY) &&
                    !"AGENCY".equals(ownerKind) && !"COUNTY".equals(ownerKind) ||
                kind == BookmarkKind.TRUNKED_SYSTEM && !ownerKind.isEmpty())
            {
                throw new IllegalArgumentException("RadioReference bookmark target is invalid");
            }
        }

        public String key()
        {
            return kind.name() + "-" + ownerKind + "-" + parentId + "-" + id;
        }
    }

    public synchronized List<Bookmark> getBookmarks()
    {
        try
        {
            Preferences bookmarks = mPreferences.node(BOOKMARKS);
            List<Bookmark> result = new ArrayList<>();
            for(String key: bookmarks.childrenNames())
            {
                Preferences entry = bookmarks.node(key);
                try
                {
                    result.add(new Bookmark(BookmarkKind.valueOf(entry.get("kind", "")),
                        entry.getInt("id", -1), entry.getInt("parent_id", 0),
                        entry.get("owner_kind", ""), entry.get("name", ""),
                        entry.get("parent_name", "")));
                }
                catch(IllegalArgumentException ignored)
                {
                    //An invalid saved item must not hide the remaining bookmarks.
                }
            }
            result.sort(Comparator.comparing(Bookmark::kind)
                .thenComparing(Bookmark::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(result);
        }
        catch(BackingStoreException exception)
        {
            throw new IllegalStateException("RadioReference bookmarks could not be loaded", exception);
        }
    }

    public synchronized List<Bookmark> saveBookmark(Bookmark bookmark)
    {
        try
        {
            Preferences entry = mPreferences.node(BOOKMARKS).node(bookmark.key());
            entry.put("kind", bookmark.kind().name());
            entry.putInt("id", bookmark.id());
            entry.putInt("parent_id", bookmark.parentId());
            entry.put("owner_kind", bookmark.ownerKind());
            entry.put("name", bookmark.name());
            entry.put("parent_name", bookmark.parentName());
            entry.flush();
            notifyPreferenceUpdated();
            return getBookmarks();
        }
        catch(BackingStoreException exception)
        {
            throw new IllegalStateException("RadioReference bookmark could not be saved", exception);
        }
    }

    public synchronized List<Bookmark> removeBookmark(Bookmark bookmark)
    {
        try
        {
            Preferences bookmarks = mPreferences.node(BOOKMARKS);
            if(bookmarks.nodeExists(bookmark.key()))
            {
                bookmarks.node(bookmark.key()).removeNode();
                bookmarks.flush();
                notifyPreferenceUpdated();
            }
            return getBookmarks();
        }
        catch(BackingStoreException exception)
        {
            throw new IllegalStateException("RadioReference bookmark could not be removed", exception);
        }
    }

}
