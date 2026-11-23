package com.nexttv
import com.nexttv.core.tv.LaunchPolicy
import org.junit.Assert.*
import org.junit.Test
class LaunchPolicyTest {
 @Test fun realTvAndNonTouchBoxesResetOnReturn(){assertTrue(LaunchPolicy.classify(true,true,false,"mt5879","TCL Q10K","tcl"));assertTrue(LaunchPolicy.classify(false,false,false,"mt5879","TCL","tcl"))}
 @Test fun emulatorKeepsBackgroundEvenWithTvFeatures(){assertFalse(LaunchPolicy.classify(true,true,false,"ranchu","Android SDK built for x86","sdk_gphone_x86"));assertFalse(LaunchPolicy.classify(true,true,false,"mumu","MuMu","mumu"))}
 @Test fun phoneKeepsBackgroundState(){assertFalse(LaunchPolicy.classify(false,false,true,"gs101","Pixel","oriole"))}
}
