package dev.pideck.app.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Function;

import dev.pideck.app.core.SessionId;
import dev.pideck.app.core.UiLanguage;

/**
 * Builds the SESSIONS screen from the runtime's listing. It holds no Android state, so grouping,
 * naming, plurals and which actions a row offers are decided here and tested on the JVM; the
 * Activity only supplies the listing and what each action does.
 */
public final class SessionsScreen {
    static final long DAY_MS = 86_400_000L;
    static final long STALE_MS = 7L * DAY_MS;

    /** What a row or the screen can ask the Activity to do. */
    public interface Actions {
        void resume(String id);

        void rename(String id, String currentTitle);

        void archive(String id);

        void archiveAll();

        void newSession();
    }

    /** Everything the screen shows, gathered by the Activity. */
    public static final class Input {
        public JSONArray sessions = new JSONArray();
        public String activeSession = "";
        public long nowMs;
        public boolean canStartNew;
        public String fault = "";
        public boolean requested;
        public int count;
        public long bytes;
        public boolean bytesPartial;
        public Function<String, String> aliases = id -> "";
        public UiLanguage language = UiLanguage.RUSSIAN;
    }

    private SessionsScreen() {
    }

    public static SessionsRootView.State build(Input in, Actions actions) {
        UiLanguage language = in.language;
        SessionsRootView.State state = new SessionsRootView.State();
        state.onNewSession = in.canStartNew ? actions::newSession : null;

        SessionsRootView.Group today = new SessionsRootView.Group(language.pick("Сегодня", "Today"));
        SessionsRootView.Group earlier = new SessionsRootView.Group(language.pick("Раньше", "Earlier"));
        for (int index = 0; index < in.sessions.length(); index++) {
            JSONObject value = in.sessions.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("id", "");
            long updated = value.optLong("updatedAtEpochMs", 0L);
            long age = in.nowMs - updated;
            boolean current = !id.isEmpty() && id.equals(in.activeSession);
            String alias = id.isEmpty() ? "" : in.aliases.apply(id);
            String title = alias != null && !alias.isBlank() ? alias : value.optString("title", "");
            if (title.isBlank()) title = language.pick("Сессия ", "Session ") + shortId(id, language);
            String rowTitle = title;
            String meta = (value.optBoolean("messagesTruncated", false) ? "≥" : "")
                    + messagesLabel(value.optInt("messages", 0), language)
                    + " · " + humanBytes(value.optLong("bytes", 0L))
                    + " · " + (age < DAY_MS
                    ? clockTime(updated, language) : calendarDate(updated, language));
            boolean resumable = !current && isResumable(id);
            SessionsRootView.SessionRow row = new SessionsRootView.SessionRow(
                    title,
                    meta,
                    current,
                    age > STALE_MS,
                    resumable ? () -> actions.resume(id) : null,
                    id.isBlank() ? null : () -> actions.rename(id, rowTitle),
                    resumable ? () -> actions.archive(id) : null
            );
            (age < DAY_MS ? today : earlier).rows.add(row);
        }
        if (!today.rows.isEmpty()) state.groups.add(today);
        if (!earlier.rows.isEmpty()) state.groups.add(earlier);

        if (!in.fault.isBlank()) {
            state.emptyNote = in.fault.contains("UNKNOWN_COMMAND")
                    && in.fault.contains("list-sessions")
                    ? language.pick(
                            "Установленный Pi runtime нужно обновить. Откройте Ядро → "
                                    + "Обновить Pi, затем вернитесь в Сессии.",
                            "The installed Pi runtime needs an update. Open Core → "
                                    + "Update Pi, then return to Sessions."
                    )
                    : language.pick(
                            "Список сессий прочитать не удалось: ",
                            "Could not read the session list: "
                    ) + in.fault;
        } else if (state.groups.isEmpty()) {
            state.emptyNote = in.requested
                    ? language.pick(
                            "В ~/.pideck/sessions пока пусто — первая сессия появится после "
                                    + "первого разговора.",
                            "~/.pideck/sessions is empty — the first session will appear "
                                    + "after your first conversation."
                    )
                    : language.pick(
                            "Список читается из Termux при открытии этого экрана.",
                            "The list is loaded from Termux when this screen opens."
                    );
        } else {
            state.emptyNote = language.pick(
                    "Тап по сессии переключает на неё Pi и показывает её последние сообщения. "
                            + "Долгое нажатие или «⋯» — переименовать или убрать в архив.",
                    "Tap a session to switch Pi to it and show its latest messages. "
                            + "Long-press or «⋯» to rename or archive it."
            );
        }

        state.footer = in.count + " " + sessionsLabel(in.count, language)
                + " · " + (in.bytesPartial ? "≥" : "") + humanBytes(in.bytes);
        if (in.count > 0) {
            state.archiveLabel = language.pick("Архивировать старые", "Archive old sessions");
            state.onArchive = actions::archiveAll;
        }
        return state;
    }

    /** The bridge keys a session by a UUID; only such names can be resumed. */
    public static boolean isResumable(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            SessionId.parse(id);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static String shortId(String id, UiLanguage language) {
        if (id == null || id.isEmpty()) return language.pick("без имени", "unnamed");
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public static String messagesLabel(int count, UiLanguage language) {
        if (language == UiLanguage.ENGLISH) return count + (count == 1 ? " message" : " messages");
        return count + " " + plural(count, "сообщение", "сообщения", "сообщений");
    }

    public static String sessionsLabel(int count, UiLanguage language) {
        if (language == UiLanguage.ENGLISH) return count == 1 ? "session" : "sessions";
        return plural(count, "сессия", "сессии", "сессий");
    }

    static String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        if (mod100 >= 11 && mod100 <= 14) return many;
        return switch (count % 10) {
            case 1 -> one;
            case 2, 3, 4 -> few;
            default -> many;
        };
    }

    public static String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        double gib = bytes / 1_073_741_824.0;
        if (gib >= 1.0) return String.format(Locale.US, "%.1f GB", gib);
        return String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0);
    }

    static String clockTime(long epochMs, UiLanguage language) {
        return new SimpleDateFormat("HH:mm", language.locale).format(new Date(epochMs));
    }

    static String calendarDate(long epochMs, UiLanguage language) {
        return new SimpleDateFormat("d MMM", language.locale).format(new Date(epochMs));
    }
}
