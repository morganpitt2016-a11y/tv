package com.morgan.minimallauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {

    @Test fun channelNamesLoseQualityAndCountryTags() {
        assertEquals("bbc one", TextMatch.normalise("BBC One HD"))
        assertEquals("itv1", TextMatch.normalise("UK: ITV1 FHD"))
        assertEquals("bbc one", TextMatch.normalise("101 BBC One HD"))
    }

    @Test fun channelShortNamesAreSpelledTheSameWay() {
        assertEquals("bbc one", TextMatch.normalise("BBC1"))
        assertEquals("itv1", TextMatch.normalise("ITV 1"))
        assertEquals("channel 4", TextMatch.normalise("Ch4"))
        assertEquals("5", TextMatch.normalise("Channel 5"))
    }

    @Test fun plusOneChannelsAreNotTheMainChannel() {
        assertEquals("quest +1", TextMatch.normalise("Quest +1"))
        assertFalse(TextMatch.matches("bbc one +1", "bbc one"))
    }

    @Test fun matchingAllowsAShortRegionalSuffixOnly() {
        assertTrue(TextMatch.matches("bbc one", "bbc one"))
        assertTrue(TextMatch.matches("bbc one london", "bbc one"))
        assertFalse(TextMatch.matches("itv1 something much too long to be the same", "itv1"))
        assertFalse(TextMatch.matches("", "bbc one"))
        assertFalse(TextMatch.matches("bbc one", ""))
    }

    @Test fun filmTitlesDropLanguageTagsYearsAndQuality() {
        assertEquals("the batman", TextMatch.vodKey("EN - The Batman (2022) 4K"))
        assertEquals("paddington 2", TextMatch.vodKey("|UK| Paddington 2"))
        assertEquals("us", TextMatch.vodKey("[EN] Us (2019)"))
        assertEquals("fast and furious", TextMatch.vodKey("Fast & Furious"))
    }

    @Test fun filmTitlesKeepNumbersThatArePartOfTheName() {
        assertEquals("2012", TextMatch.vodKey("2012"))
        assertEquals("2012", TextMatch.vodKey("2012 (2009)"))
        assertEquals("blade runner 2049", TextMatch.vodKey("Blade Runner 2049 (2017)"))
        assertEquals("1917", TextMatch.vodKey("1917"))
    }

    @Test fun punctuationInTitlesDoesNotStopAMatch() {
        assertEquals("spider man no way home", TextMatch.vodKey("Spider-Man: No Way Home"))
        assertEquals(TextMatch.vodKey("Spider-Man: No Way Home"), TextMatch.vodKey("EN - Spider Man No Way Home (2021) HD"))
    }
}
