package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.pideck.app.core.UiLanguage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SessionsScreenTest {
    private static final String CURRENT = "0190f76e-8b2a-7cc2-98c8-8c4a7ef8d123";
    private static final String OLD = "6f1c2c63-7a43-4c8e-9d0b-3a2f5e6b7c8d";
    private static final long NOW = 1_800_000_000_000L;

    private final List<String> calls = new ArrayList<>();
    private final SessionsScreen.Actions actions = new SessionsScreen.Actions() {
        @Override public void resume(String id) { calls.add("resume " + id); }
        @Override public void rename(String id, String title) { calls.add("rename " + id + " " + title); }
        @Override public void archive(String id) { calls.add("archive " + id); }
        @Override public void archiveAll() { calls.add("archiveAll"); }
        @Override public void newSession() { calls.add("new"); }
    };

    private static JSONObject session(String id, String title, long updated) throws Exception {
        return new JSONObject().put("id", id).put("title", title).put("messages", 3)
                .put("bytes", 2_097_152L).put("updatedAtEpochMs", updated);
    }

    private SessionsScreen.Input input(UiLanguage language) throws Exception {
        SessionsScreen.Input in = new SessionsScreen.Input();
        in.sessions = new JSONArray()
                .put(session(CURRENT, "текущая", NOW - 60_000L))
                .put(session(OLD, "старая", NOW - 10L * SessionsScreen.DAY_MS))
                .put(session("not-a-uuid", "", NOW - 2L * SessionsScreen.DAY_MS));
        in.activeSession = CURRENT;
        in.nowMs = NOW;
        in.canStartNew = true;
        in.requested = true;
        in.count = 3;
        in.bytes = 6_291_456L;
        in.aliases = Map.of(OLD, "Мой проект")::get;
        in.language = language;
        return in;
    }

    @Test
    public void rowsAreGroupedNamedAndOfferOnlyHonestActions() throws Exception {
        SessionsRootView.State state = SessionsScreen.build(input(UiLanguage.RUSSIAN), actions);
        assertEquals(2, state.groups.size());
        assertEquals("Сегодня", state.groups.get(0).label);
        SessionsRootView.SessionRow current = state.groups.get(0).rows.get(0);
        assertTrue(current.current);
        assertNull("the current session cannot be resumed", current.open);
        assertNull("the current session cannot be archived", current.archive);
        assertNotNull(current.rename);

        List<SessionsRootView.SessionRow> earlier = state.groups.get(1).rows;
        SessionsRootView.SessionRow old = earlier.get(0);
        assertEquals("Мой проект", old.title);
        assertTrue("a week-old session is dimmed", old.stale);
        old.open.run();
        old.archive.run();
        old.rename.run();
        assertEquals(List.of("resume " + OLD, "archive " + OLD, "rename " + OLD + " Мой проект"), calls);

        SessionsRootView.SessionRow foreign = earlier.get(1);
        assertNull("a non-UUID entry is not resumable", foreign.open);
        assertNull(foreign.archive);
        assertTrue(foreign.title.startsWith("Сессия "));
        assertEquals("3 сессии · 6 MB", state.footer);
        state.onArchive.run();
        state.onNewSession.run();
        assertTrue(calls.containsAll(List.of("archiveAll", "new")));
    }

    @Test
    public void englishUsesEnglishPluralsAndLabels() throws Exception {
        SessionsRootView.State state = SessionsScreen.build(input(UiLanguage.ENGLISH), actions);
        assertEquals("Today", state.groups.get(0).label);
        assertEquals("3 sessions · 6 MB", state.footer);
        assertTrue(state.groups.get(1).rows.get(0).meta.startsWith("3 messages · 2 MB · "));
        assertEquals("1 message", SessionsScreen.messagesLabel(1, UiLanguage.ENGLISH));
    }

    @Test
    public void russianPluralsFollowTheGrammar() {
        assertEquals("сессия", SessionsScreen.sessionsLabel(1, UiLanguage.RUSSIAN));
        assertEquals("сессии", SessionsScreen.sessionsLabel(22, UiLanguage.RUSSIAN));
        assertEquals("сессий", SessionsScreen.sessionsLabel(11, UiLanguage.RUSSIAN));
        assertEquals("сессий", SessionsScreen.sessionsLabel(5, UiLanguage.RUSSIAN));
        assertEquals("21 сообщение", SessionsScreen.messagesLabel(21, UiLanguage.RUSSIAN));
    }

    @Test
    public void anOutdatedRuntimeIsExplainedInsteadOfAnEmptyList() throws Exception {
        SessionsScreen.Input in = input(UiLanguage.ENGLISH);
        in.sessions = new JSONArray();
        in.fault = "UNKNOWN_COMMAND: Unknown runtime command: list-sessions";
        assertTrue(SessionsScreen.build(in, actions).emptyNote.contains("needs an update"));
        in.fault = "";
        in.requested = false;
        in.canStartNew = false;
        SessionsRootView.State idle = SessionsScreen.build(in, actions);
        assertTrue(idle.emptyNote.contains("loaded from Termux"));
        assertNull(idle.onNewSession);
    }
}
