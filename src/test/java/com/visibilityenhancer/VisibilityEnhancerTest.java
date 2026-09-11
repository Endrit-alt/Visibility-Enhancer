package com.visibilityenhancer;

import net.runelite.api.gameval.SpotanimID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VisibilityEnhancerTest {
    @Test
    public void sotetsegSharedAttackShouldBeExemptProjectile() {
        assertTrue(
                VisibilityEnhancer.isProjectileExempt(
                        SpotanimID.TOB_SOTETSEG_SHAREDATTACK
                )
        );
    }

    @Test
    public void sotetsegSharedAttackShouldDrawWhenTargetingOtherPlayer() {
        assertTrue(
                VisibilityEnhancer.shouldDrawProjectile(
                        SpotanimID.TOB_SOTETSEG_SHAREDATTACK,
                        true,
                        false,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void normalProjectileShouldStillBeHiddenWhenTargetingOtherPlayer() {
        assertFalse(
                VisibilityEnhancer.shouldDrawProjectile(
                        123456,
                        true,
                        false,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void sotetsegSharedAttackSpotAnimShouldBeCritical() {
        assertTrue(
                VisibilityEnhancer.isCriticalSpotAnim(
                        SpotanimID.TOB_SOTETSEG_SHAREDATTACK
                )
        );
    }

    @Test
    public void sotetsegSharedAttackImpactShouldBeCritical() {
        assertTrue(
                VisibilityEnhancer.isCriticalSpotAnim(
                        SpotanimID.TOB_SOTETSEG_SHAREDATTACK_IMPACT
                )
        );
    }

    @Test
    public void unrelatedSpotAnimShouldNotBeCritical() {
        assertFalse(
                VisibilityEnhancer.isCriticalSpotAnim(123456)
        );
    }
}
