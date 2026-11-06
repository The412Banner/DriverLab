package com.driverlab.core

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DriverSymbolsTest {
    private fun fn(name: String, symbol: String = "x") = NamedFn(0, 0, name, symbol, "", 0)

    @Test fun engineNamesStayReadable() {
        assertEquals("tu_CreateDevice", NamesFile.engineName(fn("tu_CreateDevice(VkPhysicalDevice_T*, VkDeviceCreateInfo const*, VkAllocationCallbacks const*, VkDevice_T**)")))
        assertEquals("tu_CmdDrawIndexed__chip_8", NamesFile.engineName(fn("void tu_CmdDrawIndexed<(chip)8>(VkCommandBuffer_T*, unsigned int, unsigned int, unsigned int, int, unsigned int)")))
        assertEquals("_vk_device_set_lost", NamesFile.engineName(fn("_vk_device_set_lost")))
        assertEquals("tu_autotune.end_renderpass", NamesFile.engineName(fn("tu_autotune::end_renderpass(tu_cmd_buffer*, tu_cs*, tu_autotune::rp_entry*)")))
    }

    /** The released Turnip-v27.0.0-20261106-r2-Banners-AIO driver, given by CI (DRIVERLAB_TEST_SO). */
    @Test fun releasedDriverFacts() {
        val path = System.getenv("DRIVERLAB_TEST_SO")
        assumeTrue("DRIVERLAB_TEST_SO not set", !path.isNullOrEmpty())
        val f = File(path!!)
        assertEquals("71536bb3cd49708e72da5924dbb04a0d7fad84b2", ElfFacts.buildId(f))
        assertEquals(3232, ElfFacts.codePointers(f).size)
    }
}
