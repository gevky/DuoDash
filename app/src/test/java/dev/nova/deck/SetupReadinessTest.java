package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class SetupReadinessTest {
    private SetupReadiness.Status status(boolean overlay,boolean accessEnabled,boolean accessBound,
            boolean paired,boolean persistent,boolean helper,boolean postPermission,
            boolean notifications,boolean pairingChannel,boolean shortcutConfigured,
            boolean floatingButtonEnabled,int completedVersion){
        return new SetupReadiness.Status(overlay,accessEnabled,accessBound,paired,persistent,helper,
            postPermission,notifications,pairingChannel,shortcutConfigured,floatingButtonEnabled,completedVersion);
    }

    @Test public void finishRequiresEveryLivePrerequisiteAndIgnoresOptionalPostPairNotifications(){
        SetupReadiness.Status ready=status(true,true,true,true,true,true,false,false,false,true,false,1);
        assertTrue(ready.canFinish());assertTrue("A saved valid shortcut must satisfy the new requirement without repeating pairing",ready.paired);
        assertTrue("An old schema must still pass through Finish after the shortcut requirement is introduced",ready.needsSetup());
        assertFalse(ready.canShowDashboard());assertFalse(ready.isCompleted());
        assertTrue("A clean install must pass through Finish even when current grants are already valid",SetupReadiness.shouldRouteToSetup(ready));
        SetupReadiness.Status missingShortcut=status(true,true,true,true,true,true,false,false,false,false,true,1);
        assertFalse("Missing shortcut blocks Finish",missingShortcut.canFinish());
        assertTrue(missingShortcut.needsSetup());assertFalse(missingShortcut.needsPairing());
        assertTrue(SetupReadiness.shouldRouteToSetup(missingShortcut));
        SetupReadiness.Status completedReady=status(true,true,true,true,true,true,false,false,false,true,false,2);
        assertTrue(completedReady.canShowDashboard());assertFalse(completedReady.needsSetup());
        assertFalse(SetupReadiness.shouldRouteToSetup(completedReady));
        assertTrue(SetupReadiness.shouldRouteToSetup(null));
        boolean[] requirements={true,true,true,true,true,true,true};
        String[] names={"overlay","accessibility enabled","accessibility bound","own pairing","persistent reconnect grant","live helper","configured button shortcut"};
        for(int missing=0;missing<requirements.length;missing++){
            boolean[] current=requirements.clone();current[missing]=false;
            SetupReadiness.Status incomplete=status(current[0],current[1],current[2],current[3],current[4],current[5],
                false,false,false,current[6],true,SetupReadiness.CURRENT_SCHEMA_VERSION);
            assertFalse(names[missing]+" missing must block Finish even with a completion stamp",incomplete.canFinish());
            boolean localRequirement=missing==0||missing==1||missing==2||missing==6;
            assertEquals(names[missing]+" missing dashboard requirement routes to setup",localRequirement,incomplete.needsSetup());
            assertEquals(names[missing]+" missing dashboard requirement route",localRequirement,SetupReadiness.shouldRouteToSetup(incomplete));
        }
        SetupReadiness.Status shortcutReadyWithoutFloating=status(true,true,true,true,true,true,false,false,false,true,false,2);
        assertTrue("Floating handle visibility is a user preference, not a completion gate",shortcutReadyWithoutFloating.canFinish());
    }

    @Test public void notificationsAreOptionalForThePanelPairingPathAndFallbackAvailabilityIsSeparate(){
        SetupReadiness.Status ready=status(true,true,true,true,true,true,false,false,false,true,true,2);
        assertTrue("Notification permission/channel are optional after pairing",ready.canFinish());

        SetupReadiness.Status pairingCanceled=status(true,true,true,false,true,true,true,true,true,true,true,2);
        assertFalse("A saved completion stamp cannot bypass canceled pairing",pairingCanceled.canFinish());
        assertTrue(pairingCanceled.needsPairing());assertTrue(pairingCanceled.canStartPairing());
        assertTrue("The notification path remains available when its permission is granted",pairingCanceled.canUsePairingNotification());
        SetupReadiness.Status noNotifications=status(false,false,false,false,false,false,false,false,false,false,true,0);
        assertTrue("The panel path must not be blocked by notification permission or channel",noNotifications.canStartPairing());
        assertFalse(noNotifications.canUsePairingNotification());
        assertFalse(status(false,false,false,true,false,false,false,true,true,true,true,2).canUsePairingNotification());
        SetupReadiness.Status pairedNoNotifications=status(false,false,false,true,false,false,false,false,false,false,true,0);
        assertTrue("A new pairing code remains available for an explicitly requested re-pair",pairedNoNotifications.canPairAgain());
    }

    @Test public void completedSetupWithHelperLossKeepsDashboardAndRoutesRecoveryToConnection(){
        SetupReadiness.Status lostHelper=status(true,true,true,true,true,false,false,false,false,true,true,2);
        assertFalse(lostHelper.canFinish());
        assertTrue(lostHelper.canShowDashboard());assertFalse(lostHelper.needsSetup());
        assertFalse(SetupReadiness.shouldRouteToSetup(lostHelper));
        assertTrue(lostHelper.recoveryMode());assertFalse(lostHelper.needsPairing());
        assertFalse(lostHelper.canStartPairing());
    }

    @Test public void completedInstallCanReachConnectionRecoveryWhenPairingOrReconnectGrantNeedsRepair(){
        SetupReadiness.Status missingPairing=status(true,true,true,false,true,false,false,false,false,true,true,2);
        assertTrue(missingPairing.canShowDashboard());assertFalse(SetupReadiness.shouldRouteToSetup(missingPairing));
        assertTrue(missingPairing.recoveryMode());assertTrue(missingPairing.needsPairing());
        SetupReadiness.Status missingReconnectGrant=status(true,true,true,true,false,false,false,false,false,true,true,2);
        assertTrue(missingReconnectGrant.canShowDashboard());assertFalse(SetupReadiness.shouldRouteToSetup(missingReconnectGrant));
        assertTrue(missingReconnectGrant.recoveryMode());
    }

    @Test public void completedInstallStillRequiresOverlayAccessibilityAndShortcutForDashboard(){
        assertTrue(SetupReadiness.shouldRouteToSetup(status(false,true,true,true,true,false,false,false,false,true,true,2)));
        assertTrue(SetupReadiness.shouldRouteToSetup(status(true,false,true,true,true,false,false,false,false,true,true,2)));
        assertTrue(SetupReadiness.shouldRouteToSetup(status(true,true,false,true,true,false,false,false,false,true,true,2)));
        assertTrue(SetupReadiness.shouldRouteToSetup(status(true,true,true,true,true,false,false,false,false,false,true,2)));
    }

    @Test public void completedPairedInstallCanBootReconnectWithoutOpeningDashboard(){
        SetupReadiness.Status coldBoot=status(true,true,false,true,true,false,false,false,false,true,true,
            SetupReadiness.CURRENT_SCHEMA_VERSION);
        assertTrue("Boot recovery must be able to start the helper before accessibility rebinds",coldBoot.canReconnectInBackground());
        assertFalse("An offline helper or unbound accessibility service still blocks the dashboard",coldBoot.canFinish());
        assertTrue(coldBoot.needsSetup());
        assertFalse("A first-run paired app is not eligible for automatic boot recovery",status(true,true,false,true,true,false,false,false,false,true,true,1).canReconnectInBackground());
        assertFalse("An unpaired install cannot use saved-pairing boot recovery",status(true,true,false,false,true,false,false,false,false,true,true,2).canReconnectInBackground());
    }
}
