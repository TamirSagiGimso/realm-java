/*
 * Copyright 2016 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import io.realm.entities.StringOnly;
import io.realm.objectserver.utils.StringOnlyModule;
import io.realm.rule.RunInLooperThread;
import io.realm.util.SyncTestUtils;

import static io.realm.util.SyncTestUtils.createNamedTestUser;
import static io.realm.util.SyncTestUtils.createTestUser;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SyncConfigurationTests {
    @Rule
    public final TestSyncConfigurationFactory configFactory = new TestSyncConfigurationFactory();

    @Rule
    public final RunInLooperThread looperThread = new RunInLooperThread();

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    @After
    public void tearDown() {
        for (SyncUser syncUser : SyncUser.all().values()) {
            syncUser.logOut();
        }
        SyncManager.reset();
    }

    @Test
    public void user_invalidUserThrows() {
        try {
            new SyncConfiguration.Builder(null, "realm://ros.realm.io/default");
        } catch (IllegalArgumentException ignore) {
        }

        SyncUser user = createTestUser(0); // Create user that has expired credentials
        try {
            new SyncConfiguration.Builder(user, "realm://ros.realm.io/default");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void serverUrl_setsFolderAndFileName() {
        SyncUser user = createTestUser();
        String identity = user.getIdentity();
        String[][] validUrls = {
                // <URL>, <Folder>, <FileName>
                { "realm://objectserver.realm.io/~/default", "realm-object-server/" + identity + "/" + identity, "default" },
                { "realm://objectserver.realm.io/~/sub/default", "realm-object-server/" + identity + "/" + identity + "/sub", "default" }
        };

        for (String[] validUrl : validUrls) {
            String serverUrl  = validUrl[0];
            String expectedFolder = validUrl[1];
            String expectedFileName = validUrl[2];

            SyncConfiguration config = new SyncConfiguration.Builder(user, serverUrl).build();

            assertEquals(new File(InstrumentationRegistry.getContext().getFilesDir(), expectedFolder), config.getRealmDirectory());
            assertEquals(expectedFileName, config.getRealmFileName());
        }
    }

    @Test
    public void serverUrl_flexibleInput() {
        // Check that the serverUrl accept a wide range of input
        Object[][] fuzzyInput = {
                // Only path -> Use auth server as basis for server url, but ignore port if set
                { createTestUser("http://ros.realm.io/auth"),      "/~/default", "realm://ros.realm.io/~/default" },
                { createTestUser("http://ros.realm.io:7777/auth"), "/~/default", "realm://ros.realm.io/~/default" },
                { createTestUser("https://ros.realm.io/auth"),     "/~/default", "realms://ros.realm.io/~/default" },
                { createTestUser("https://127.0.0.1/auth"),        "/~/default", "realms://127.0.0.1/~/default" },

                { createTestUser("http://ros.realm.io/auth"),      "~/default",  "realm://ros.realm.io/~/default" },
                { createTestUser("http://ros.realm.io:7777/auth"), "~/default",  "realm://ros.realm.io/~/default" },
                { createTestUser("https://ros.realm.io/auth"),     "~/default",  "realms://ros.realm.io/~/default" },
                { createTestUser("https://127.0.0.1/auth"),        "~/default",  "realms://127.0.0.1/~/default" },

                // Check that the same name used for server and name doesn't crash
                { createTestUser("http://ros.realm.io/auth"),      "~/ros.realm.io",  "realm://ros.realm.io/~/ros.realm.io" },

                // Forgot schema -> Use the one from the auth url
                { createTestUser("http://ros.realm.io/auth"), "ros.realm.io/~/default", "realm://ros.realm.io/~/default" },
                { createTestUser("http://ros.realm.io/auth"), "//ros.realm.io/~/default", "realm://ros.realm.io/~/default" },
                { createTestUser("https://ros.realm.io/auth"), "ros.realm.io/~/default", "realms://ros.realm.io/~/default" },
                { createTestUser("https://ros.realm.io/auth"), "//ros.realm.io/~/default", "realms://ros.realm.io/~/default" },

                // Automatically replace http|https with realm|realms
                { createTestUser(), "http://ros.realm.io/~/default", "realm://ros.realm.io/~/default" },
                { createTestUser(), "https://ros.realm.io/~/default", "realms://ros.realm.io/~/default" }
        };

        for (Object[] test : fuzzyInput) {
            SyncUser user = (SyncUser) test[0];
            String serverUrlInput = (String) test[1];
            String resolvedServerUrl = ((String) test[2]).replace("~", user.getIdentity());

            SyncConfiguration config = new SyncConfiguration.Builder(user, serverUrlInput).build();

            assertEquals(String.format("Input '%s' did not resolve correctly.", serverUrlInput),
                    resolvedServerUrl, config.getServerUrl().toString());
        }
    }

    @Test
    public void serverUrl_invalidUrlThrows() {
        String[] invalidUrls = {
            null,
// TODO Should these two fail?
//            "objectserver.realm.io/~/default", // Missing protocol. TODO Should we just default to one?
//            "/~/default", // Missing server
            "realm://objectserver.realm.io/~/default.realm", // Ending with .realm
            "realm://objectserver.realm.io/~/default.realm.lock", // Ending with .realm.lock
            "realm://objectserver.realm.io/~/default.realm.management", // Ending with .realm.management
            "realm://objectserver.realm.io/<~>/default.realm", // Invalid chars <>
            "realm://objectserver.realm.io/~/default.realm/", // Ending with /
            "realm://objectserver.realm.io/~/Αθήνα", // Non-ascii
            "realm://objectserver.realm.io/~/foo/../bar", // .. is not allowed
            "realm://objectserver.realm.io/~/foo/./bar", // . is not allowed
        };

        for (String invalidUrl : invalidUrls) {
            try {
                new SyncConfiguration.Builder(createTestUser(), invalidUrl);
                fail(invalidUrl + " should have failed.");
            } catch (IllegalArgumentException ignore) {
            }
        }
    }

    private String makeServerUrl(int len) {
        StringBuilder builder = new StringBuilder("realm://objectserver.realm.io/~/");
        for (int i = 0; i < len; i++) {
            builder.append('A');
        }
        return builder.toString();
    }

    @Test
    public void serverUrl_length() {
        int[] lengths = {1, SyncConfiguration.MAX_FILE_NAME_LENGTH - 1,
                SyncConfiguration.MAX_FILE_NAME_LENGTH, SyncConfiguration.MAX_FILE_NAME_LENGTH + 1, 1000};

        for (int len : lengths) {
            SyncConfiguration config = new SyncConfiguration.Builder(createTestUser(), makeServerUrl(len)).build();
            assertTrue("Length: " + len, config.getRealmFileName().length() <= SyncConfiguration.MAX_FILE_NAME_LENGTH);
            assertTrue("Length: " + len, config.getPath().length() <= SyncConfiguration.MAX_FULL_PATH_LENGTH);
        }
    }

    @Test
    public void serverUrl_invalidChars() {
        SyncConfiguration.Builder builder = new SyncConfiguration.Builder(createTestUser(), "realm://objectserver.realm.io/~/?");
        SyncConfiguration config = builder.build();
        assertFalse(config.getRealmFileName().contains("?"));
    }

    @Test
    public void serverUrl_port() {
        Map<String, Integer> urlPort = new HashMap<String, Integer>();
        urlPort.put("realm://objectserver.realm.io/~/default", -1); // default port - handled by sync client
        urlPort.put("realms://objectserver.realm.io/~/default", -1); // default port - handled by sync client
        urlPort.put("realm://objectserver.realm.io:8080/~/default", 8080);
        urlPort.put("realms://objectserver.realm.io:2443/~/default", 2443);

        for (String url : urlPort.keySet()) {
            SyncConfiguration config = new SyncConfiguration.Builder(createTestUser(), url).build();
            assertEquals(urlPort.get(url).intValue(), config.getServerUrl().getPort());
        }
    }

    @Test
    public void errorHandler() {
        SyncConfiguration.Builder builder = new SyncConfiguration.Builder(createTestUser(), "realm://objectserver.realm.io/default");
        SyncSession.ErrorHandler errorHandler = new SyncSession.ErrorHandler() {
            @Override
            public void onError(SyncSession session, ObjectServerError error) {

            }
        };
        SyncConfiguration config = builder.errorHandler(errorHandler).build();
        assertEquals(errorHandler, config.getErrorHandler());
    }

    @Test
    public void errorHandler_fromSyncManager() {
        // Set default error handler
        SyncSession.ErrorHandler errorHandler = new SyncSession.ErrorHandler() {
            @Override
            public void onError(SyncSession session, ObjectServerError error) {

            }
        };
        SyncManager.setDefaultSessionErrorHandler(errorHandler);

        // Create configuration using the default handler
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url).build();
        assertEquals(errorHandler, config.getErrorHandler());
        SyncManager.setDefaultSessionErrorHandler(null);
    }


    @Test
    public void errorHandler_nullThrows() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration.Builder builder = new SyncConfiguration.Builder(user, url);

        try {
            builder.errorHandler(null);
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void equals() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url)
                .build();
        assertTrue(config.equals(config));
    }

    @Test
    public void equals_same() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config1 = new SyncConfiguration.Builder(user, url).build();
        SyncConfiguration config2 = new SyncConfiguration.Builder(user, url).build();

        assertTrue(config1.equals(config2));
    }

    @Test
    public void equals_not() {
        SyncUser user = createTestUser();
        String url1 = "realm://objectserver.realm.io/default1";
        String url2 = "realm://objectserver.realm.io/default2";
        SyncConfiguration config1 = new SyncConfiguration.Builder(user, url1).build();
        SyncConfiguration config2 = new SyncConfiguration.Builder(user, url2).build();
        assertFalse(config1.equals(config2));
    }

    @Test
    public void hashCode_equal() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url)
                .build();

        assertEquals(config.hashCode(), config.hashCode());
    }

    @Test
    public void hashCode_notEquals() {
        SyncUser user = createTestUser();
        String url1 = "realm://objectserver.realm.io/default1";
        String url2 = "realm://objectserver.realm.io/default2";
        SyncConfiguration config1 = new SyncConfiguration.Builder(user, url1).build();
        SyncConfiguration config2 = new SyncConfiguration.Builder(user, url2).build();
        assertNotEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    public void get_syncSpecificValues() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url).build();
        assertTrue(user.equals(config.getUser()));
        assertEquals("realm://objectserver.realm.io/default", config.getServerUrl().toString());
        assertFalse(config.shouldDeleteRealmOnLogout());
        assertTrue(config.isSyncConfiguration());
    }

    @Test
    public void encryption() {
       SyncUser user = createTestUser();
       String url = "realm://objectserver.realm.io/default";
       SyncConfiguration config = new SyncConfiguration.Builder(user, url)
               .encryptionKey(TestHelper.getRandomKey())
               .build();
       assertNotNull(config.getEncryptionKey());
    }

    @Test(expected = IllegalArgumentException.class)
    public void encryption_invalid_null() {
       SyncUser user = createTestUser();
       String url = "realm://objectserver.realm.io/default";

       new SyncConfiguration.Builder(user, url).encryptionKey(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void encryption_invalid_wrong_length() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";

        new SyncConfiguration.Builder(user, url).encryptionKey(new byte[]{1, 2, 3});
    }

    @Test(expected = IllegalArgumentException.class)
    public void directory_null() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        new SyncConfiguration.Builder(user, url).directory(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void directory_writeProtectedDir() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";

        File dir = new File("/");
        new SyncConfiguration.Builder(user, url).directory(dir);
    }

    @Test
    public void directory_dirIsAFile() throws IOException {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";

        File dir = configFactory.getRoot();
        File file = new File(dir, "dummyfile");
        assertTrue(file.createNewFile());
        thrown.expect(IllegalArgumentException.class);
        new SyncConfiguration.Builder(user, url).directory(file);
        file.delete(); // clean up
    }

    @Ignore("deleteRealmOnLogout is not supported yet")
    @Test
    public void deleteOnLogout() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";

        SyncConfiguration config = new SyncConfiguration.Builder(user, url)
                //.deleteRealmOnLogout()
                .build();
        assertTrue(config.shouldDeleteRealmOnLogout());
    }

    @Test
    public void initialData() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";

        SyncConfiguration config = configFactory.createSyncConfigurationBuilder(user, url)
                .schema(StringOnly.class)
                .initialData(new Realm.Transaction() {
                    @Override
                    public void execute(Realm realm) {
                        StringOnly stringOnly = realm.createObject(StringOnly.class);
                        stringOnly.setChars("TEST 42");
                    }
                })
                .build();

        assertNotNull(config.getInitialDataTransaction());

        // open the first time - initialData must be triggered
        Realm realm1 = Realm.getInstance(config);
        RealmResults<StringOnly> results = realm1.where(StringOnly.class).findAll();
        assertEquals(1, results.size());
        assertEquals("TEST 42", results.first().getChars());
        realm1.close();

        // open the second time - initialData must not be triggered
        Realm realm2 = Realm.getInstance(config);
        assertEquals(1, realm2.where(StringOnly.class).count());
        realm2.close();
    }

    @Test
    public void defaultRxFactory() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url).build();

        assertNotNull(config.getRxFactory());
    }

    @Test
    public void toString_nonEmpty() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url).build();

        String configStr = config.toString();
        assertTrue(configStr != null && !configStr.isEmpty());
    }

    // FIXME: This test can be removed when https://github.com/realm/realm-core/issues/2345 is resolved
    @Test(expected = UnsupportedOperationException.class)
    public void compact_NotAllowed() {
        SyncUser user = createTestUser();
        String url = "realm://objectserver.realm.io/default";
        SyncConfiguration config = new SyncConfiguration.Builder(user, url).build();

        Realm.compactRealm(config);
    }

    // Check that it is possible for multiple users to reference the same Realm URL while each user still use their
    // own copy on the filesystem. This is e.g. what happens if a Realm is shared using a PermissionOffer.
    @Test
    public void multipleUsersReferenceSameRealm() {
        SyncUser user1 = createNamedTestUser("user1");
        SyncUser user2 = createNamedTestUser("user2");
        String sharedUrl = "realm://ros.realm.io/42/default";
        SyncConfiguration config1 = new SyncConfiguration.Builder(user1, sharedUrl).modules(new StringOnlyModule()).build();
        Realm realm1 = Realm.getInstance(config1);
        SyncConfiguration config2 = new SyncConfiguration.Builder(user2, sharedUrl).modules(new StringOnlyModule()).build();
        Realm realm2 = null;

        // Verify that two different configurations can be used for the same URL
        try {
            realm2 = Realm.getInstance(config1);
        } finally {
            realm1.close();
            if (realm2 != null) {
                realm2.close();
            }
        }

        // Verify that we actually save two different files
        assertNotEquals(config1.getPath(), config2.getPath());
    }

    @Test
    public void automatic_throwsIfNoUserIsLoggedIn() {
        try {
            SyncConfiguration.automatic();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().startsWith("No user was logged in"));
        }
    }

    @Test
    public void automatic_throwsIfMultipleUsersIsLoggedIn() {
        SyncTestUtils.createTestUser();
        SyncTestUtils.createTestUser();
        try {
            SyncConfiguration.automatic();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("Current user is not valid if more that one valid, logged-in user exists.", e.getMessage());
        }
    }

    @Test
    public void automaticWithUser_throwsIfNullOrInvalid() {
        try {
            //noinspection ConstantConditions
            SyncConfiguration.automatic(null);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("Non-null 'user' required."));
        }
        SyncUser user = SyncTestUtils.createTestUser();
        user.logOut();
        try {
            SyncConfiguration.automatic(user);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("User is no logger valid.  Log the user in again.", e.getMessage());
        }
    }

    @Test
    public void automatic_isPartial() {
        SyncUser user = SyncTestUtils.createTestUser();

        SyncConfiguration config = SyncConfiguration.automatic();
        assertTrue(config.isPartialRealm());

        config = SyncConfiguration.automatic(user);
        assertTrue(config.isPartialRealm());
    }

    @Test
    public void automatic_convertsAuthUrl() {
        Object[][] input = {
                // AuthUrl -> Expected Realm URL
                { "http://ros.realm.io/auth", "realm://ros.realm.io/default" },
                { "http://ros.realm.io:7777", "realm://ros.realm.io/default" },
                { "http://127.0.0.1/auth", "realm://127.0.0.1/default" },
                { "HTTP://ros.realm.io" , "realm://ros.realm.io/default" },

                { "https://ros.realm.io/auth", "realms://ros.realm.io/default" },
                { "https://ros.realm.io:7777", "realms://ros.realm.io/default" },
                { "https://127.0.0.1/auth", "realms://127.0.0.1/default" },
                { "HTTPS://ros.realm.io" , "realms://ros.realm.io/default" },
        };

        for (Object[] test : input) {
            String authUrl = (String) test[0];
            String realmUrl = (String) test[1];

            SyncUser user = SyncTestUtils.createTestUser(authUrl);
            SyncConfiguration config = SyncConfiguration.automatic();
            URI url = config.getServerUrl();
            assertEquals(realmUrl, url.toString());
            user.logOut();
        }
    }
}
