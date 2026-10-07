package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ModelLifecycleTest {
    private static ModelLifecycle.Facts facts() {
        ModelLifecycle.Facts facts = new ModelLifecycle.Facts();
        facts.fits = true;
        return facts;
    }

    @Test
    public void aPhoneThatCannotHoldTheWeightsOffersNothing() {
        ModelLifecycle.Facts facts = facts();
        facts.fits = false;
        facts.privateReady = true;
        ModelLifecycle.Stage stage = ModelLifecycle.stage(facts);
        assertEquals(ModelLifecycle.Stage.NO_RAM, stage);
        assertEquals(ModelLifecycle.Action.NONE, ModelLifecycle.action(stage, false));
        assertEquals(ModelLifecycle.Secondary.NONE, ModelLifecycle.secondary(stage, facts));
    }

    @Test
    public void aPrivateCopyIsSelectedRestartedOrAlreadyActive() {
        ModelLifecycle.Facts facts = facts();
        facts.privateReady = true;
        facts.downloadFailed = true;
        assertEquals(ModelLifecycle.Stage.INSTALLED, ModelLifecycle.stage(facts));
        assertEquals(ModelLifecycle.Action.SELECT,
                ModelLifecycle.action(ModelLifecycle.Stage.INSTALLED, false));
        assertEquals(ModelLifecycle.Action.RESTART,
                ModelLifecycle.action(ModelLifecycle.Stage.INSTALLED, true));
        facts.selected = true;
        facts.serverReady = true;
        assertEquals(ModelLifecycle.Stage.ACTIVE, ModelLifecycle.stage(facts));
        assertEquals(ModelLifecycle.Action.NONE,
                ModelLifecycle.action(ModelLifecycle.Stage.ACTIVE, true));
    }

    @Test
    public void downloadStatesLeadTowardsAVerifiedInstall() {
        ModelLifecycle.Facts facts = facts();
        assertEquals(ModelLifecycle.Stage.MISSING, ModelLifecycle.stage(facts));
        assertEquals(ModelLifecycle.Secondary.ATTACH_FILE,
                ModelLifecycle.secondary(ModelLifecycle.Stage.MISSING, facts));
        facts.downloadActive = true;
        assertEquals(ModelLifecycle.Stage.DOWNLOADING, ModelLifecycle.stage(facts));
        assertEquals(ModelLifecycle.Action.CANCEL_DOWNLOAD,
                ModelLifecycle.action(ModelLifecycle.Stage.DOWNLOADING, false));
        assertEquals(ModelLifecycle.Secondary.NONE,
                ModelLifecycle.secondary(ModelLifecycle.Stage.DOWNLOADING, facts));
        facts.downloadActive = false;
        facts.downloadFailed = true;
        assertEquals(ModelLifecycle.Action.RETRY_DOWNLOAD,
                ModelLifecycle.action(ModelLifecycle.stage(facts), false));
        facts.downloadFailed = false;
        facts.incoming = true;
        assertEquals(ModelLifecycle.Stage.INCOMING, ModelLifecycle.stage(facts));
        assertEquals(ModelLifecycle.Action.VERIFY,
                ModelLifecycle.action(ModelLifecycle.Stage.INCOMING, false));
        assertEquals(ModelLifecycle.Secondary.DELETE_SOURCE,
                ModelLifecycle.secondary(ModelLifecycle.Stage.INCOMING, facts));
        facts.verified = true;
        assertEquals(ModelLifecycle.Stage.VERIFIED, ModelLifecycle.stage(facts));
        assertEquals(ModelLifecycle.Action.INSTALL,
                ModelLifecycle.action(ModelLifecycle.Stage.VERIFIED, false));
    }
}
