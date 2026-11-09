package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderDirectoryTest {
    private val valid = """
        {
          "version": 20261019,
          "ttl_seconds": 3600,
          "services": [
            {"id":"combo","display_name":"Astranet Combo","server_url":"https://toastybread.fyi"},
            {"id":"access","display_name":"Astranet Access/Vyle Select","server_url":"https://3ddtee.xyz"},
            {"id":"fanatic","display_name":"Astranet Fanatic","server_url":"https://fanatic.astranettv.com"},
            {"id":"titan","display_name":"Astranet Titan","server_url":"https://734.silksurfer.com"},
            {"id":"apex","display_name":"Apex/Atlas","server_url":"https://hyper-apex.com"},
            {"id":"champ","display_name":"Hyper Champ","server_url":"https://bulkalways.com"},
            {"id":"vyle","display_name":"Vyle TV","server_url":"https://primevip.day"}
          ]
        }
    """.trimIndent()

    @Test fun parsesAllConfiguredServicesInOrder() {
        val result = ProviderDirectory.parse(valid)
        assertNotNull(result)
        assertEquals(listOf("combo", "access", "fanatic", "titan", "apex", "champ", "vyle"), result!!.services.map { it.id })
        assertEquals("https://toastybread.fyi", result.services.first().serverUrl)
    }

    @Test fun rejectsUntrustedOrAmbiguousServiceEntries() {
        val json = """
            {
              "version": 1,
              "services": [
                {"id":"ok","display_name":"Okay","server_url":"https://example.com"},
                {"id":"bad","display_name":"HTTP","server_url":"http://example.com"},
                {"id":"evil","display_name":"Credentials","server_url":"https://user:pass@example.com"},
                {"id":"ok","display_name":"Duplicate","server_url":"https://other.example"}
              ]
            }
        """.trimIndent()
        val result = ProviderDirectory.parse(json)
        assertEquals(listOf("ok"), result?.services?.map { it.id })
    }

    @Test fun preservesPlaylistCredentialsWhenBuildingXtreamUrl() {
        assertEquals(
            "https://toastybread.fyi/get.php?username=demo+user&password=p%26ss%3Dword&type=m3u_plus&output=ts",
            PlaylistLogic.xtreamUrl("https://toastybread.fyi", "demo user", "p&ss=word")
        )
    }

    @Test fun rejectsInvalidConfigAndEmptyDirectory() {
        assertNull(ProviderDirectory.parse(null))
        assertNull(ProviderDirectory.parse("{}"))
        assertNull(ProviderDirectory.parse("""{"version":1,"services":[]}"""))
    }
}
