package com.voicestudio.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuVariantTest {
    private val snapdragon8Elite = """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 rng bti ecv afp rpres
        CPU implementer	: 0x51

        processor	: 1
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 rng bti ecv afp rpres
    """.trimIndent()

    @Test fun picksTheFastestVariantEveryCoreSupports() {
        assertEquals(listOf("_i8mm", "_dotprod", ""), cpuVariants(cpuFeatures(snapdragon8Elite)))
        // Cortex-A55/A76 era: dot product and fp16 but no int8 matrix multiply.
        assertEquals(listOf("_dotprod", ""), cpuVariants(cpuFeatures("Features\t: fp asimd fphp asimdhp asimddp")))
        // Cortex-A53: baseline only.
        assertEquals(listOf(""), cpuVariants(cpuFeatures("Features\t: fp asimd evtstrm aes crc32")))
        // A feature missing on one core rules the variant out for all.
        assertEquals(listOf("_dotprod", ""), cpuVariants(cpuFeatures("Features\t: asimdhp asimddp i8mm\nFeatures\t: asimdhp asimddp")))
        assertEquals(listOf("_avx2", ""), cpuVariants(cpuFeatures("flags\t\t: fpu sse4_2 avx avx2 fma f16c bmi2 popcnt")))
        assertEquals(listOf(""), cpuVariants(cpuFeatures("")))
    }
}
