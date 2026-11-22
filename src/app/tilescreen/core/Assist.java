package app.tilescreen.core;

import java.util.Map;

/**
 * Home Assistant's Assist on the screen (Tessera's voice_assist plugin 0.4.0; ESPHome's voice_assistant): a tap or the
 * wake word (WakeWord, heard on the tablet) asks, the microphone goes to Home Assistant over the API connection until
 * Home Assistant hears the end of speech, and the answer plays on the screen's media player as an announcement. This
 * class is where a question is (phase, what was heard, the answer, a failure) and nothing of Android: the app hands it
 * its microphone and its two sounds, the screen draws it (VoiceAssistPlugin).
 */
public final class Assist implements EspDevice.VoiceListener {
    public static final int IDLE = 0, LISTENING = 1, THINKING = 2, ANSWERING = 3, FAILED = 4;

    // VoiceAssistantEvent (api.proto).
    static final int ERROR = 0, RUN_START = 1, RUN_END = 2, STT_START = 3, STT_END = 4, INTENT_START = 5, INTENT_END = 6,
            TTS_START = 7, TTS_END = 8, STT_VAD_START = 11, STT_VAD_END = 12, INTENT_PROGRESS = 100;

    /** How long a reply or a failure stays on the tile after it was spoken (voice_assist.cpp REPLY_SHOWN_MS). */
    public static final long REPLY_SHOWN_MS = 20000;
    /** How long the popup stays after the answer or a failure, and after a question that came to nothing. */
    public static final long POPUP_AFTER_MS = 30000, POPUP_EMPTY_MS = 2000;
    /** The pace of the answer's words in the popup, about that of Home Assistant's voices. */
    public static final long SPOKEN_CHARS_PER_S = 15;
    /** The longest a question listens, should Home Assistant never hear its end. */
    static final long LISTEN_MOST_MS = 15000;
    /** How long after the answer went to the speaker its words start anyway, when the player never says it plays. */
    static final long HEARD_AFTER_MS = 1500;

    /** The tablet's microphone, 16 kHz 16-bit mono in 20 ms parts, while a question listens. */
    public interface Microphone {
        /** Start giving parts to `sink` (on a thread of its own); false when it can't. */
        boolean start(Sink sink);
        void stop();
    }

    public interface Sink { void part(byte[] pcm); }

    /** The plugin's two sounds: when it starts to listen, and again and again while Assist thinks. */
    public interface Sounds {
        void wake();
        void thinking();
    }

    private final EspDevice device;
    private final EspDevice.MediaPlayer player;
    private final ScreenSettings settings;
    private final EspDevice.Logger log;
    public volatile Microphone microphone;
    public volatile Sounds sounds;
    /** Something changed: the screen draws again. */
    public volatile Runnable changed;

    private volatile int phase = IDLE;
    private volatile boolean running, streaming, popup, continueAfter;
    private volatile String heard = "", answer = "", streamUrl = "", conversation = "";
    private volatile long answeredAt, listenedAt, speakAsked, speakFrom, closeAt, lastThinking, playedAt;
    private volatile boolean sawAnnouncing;
    private Thread ticker;

    public Assist(EspDevice device, EspDevice.MediaPlayer player, ScreenSettings settings, EspDevice.Logger log) {
        this.device = device;
        this.player = player;
        this.settings = settings;
        this.log = log;
        device.voiceListener = this;
    }

    // ----- what the screen reads -----

    public int phase() { return phase; }
    public String heard() { return heard; }
    public boolean busy() { return running || phase == LISTENING || phase == THINKING || phase == ANSWERING; }
    public boolean ready() { return device.voiceReady(); }
    public boolean popupOpen() { return popup; }
    public boolean replyFresh() { return answeredAt != 0 && now() - answeredAt < REPLY_SHOWN_MS; }
    public boolean showingFailure() { return phase == FAILED && replyFresh(); }
    public String reply() { return answer; }

    /** The answer as far as it is spoken: it grows at the pace of speech, whole once the speaker is done. */
    public String said() {
        String a = answer;
        if (a.isEmpty()) return "";
        if (phase == FAILED || phase == IDLE) return a;
        long from = speakFrom;
        if (from == 0) return "";
        long chars = (now() - from) * SPOKEN_CHARS_PER_S / 1000;
        if (chars >= a.length()) return a;
        int end = a.indexOf(' ', (int) chars);
        return end < 0 ? a : a.substring(0, end);
    }

    // ----- what a finger does -----

    /** A tap on the tile, the settings page's Ask now or the popup: ask now, or stop what runs. */
    public void ask() {
        if (busy()) {
            stop();
            return;
        }
        start("");
    }

    /**
     * The wake word heard `phrase` (WakeWord): ask, as a tap would, with the phrase for Home Assistant. A question that
     * already runs goes on (voice_assistant ignores a start while it runs).
     */
    public void wake(String phrase) {
        if (busy() || !device.voiceReady()) return;
        start(phrase);
    }

    private void start(String phrase) {
        if (!device.voiceReady()) {
            log.log("assist: no Home Assistant takes the voice assistant now");
            failed("", "Home Assistant is away");
            return;
        }
        // A question lights the screen, as a touch does, so its popup is seen (from standby, after the wake word).
        settings.wakeBy(phrase.isEmpty() ? "the voice assistant" : "the wake word");
        listening();
        running = true;
        streamUrl = "";
        continueAfter = false;
        if (!device.voiceStart(conversation, phrase)) {
            running = false;
            failed("", "Home Assistant is away");
            return;
        }
        log.log(phrase.isEmpty() ? "assist: asking" : "assist: asking after \"" + phrase + "\"");
        changed();
    }

    public void stop() {
        if (running) device.voiceStop();
        endStream();
        running = false;
        if (phase == LISTENING || phase == THINKING) phase = IDLE;
        changed();
    }

    /** The popup went (a tap beside it, its close key): what runs stops too. */
    public void closePopup() {
        popup = false;
        closeAt = 0;
        if (busy()) stop();
        changed();
    }

    /** A tap on the popup: stops what runs; once it is done, closes it. */
    public void tapPopup() {
        if (busy()) stop();
        else closePopup();
    }

    // ----- Home Assistant (EspDevice.VoiceListener, on the connection's thread) -----

    public void subscribed(boolean on) {
        // Home Assistant went in the middle of a question: it can't be answered any more. An answer already on the
        // speaker plays on.
        if (!on && running) {
            endStream();
            running = false;
            failed("", "Home Assistant is away");
        }
        changed();
    }

    public void response(boolean error) {
        if (error) {
            running = false;
            failed("", "Home Assistant couldn't start Assist");
            return;
        }
        startStream();
    }

    public void event(int type, Map<String, String> data) {
        switch (type) {
            case RUN_START:
                // The answer's address when Home Assistant streams it as it is written (intent progress says when).
                String url = data.get("url");
                if (url != null) streamUrl = url;
                break;
            case STT_VAD_END:
                endStream();
                if (phase == LISTENING) phase = THINKING;
                break;
            case STT_END:
                endStream();
                heard = nz(data.get("text"));
                if (phase == LISTENING) phase = THINKING;
                break;
            case INTENT_START:
                endStream();
                if (phase == LISTENING) phase = THINKING;
                break;
            case INTENT_PROGRESS:
                if ("1".equals(data.get("tts_start_streaming")) && !streamUrl.isEmpty()) speaking(streamUrl);
                break;
            case INTENT_END:
                String id = data.get("conversation_id");
                if (id != null) conversation = id;
                continueAfter = "1".equals(data.get("continue_conversation"));
                break;
            case TTS_START:
                answer = nz(data.get("text"));
                answeredAt = now();
                if (phase != FAILED) phase = ANSWERING;
                break;
            case TTS_END:
                speaking(nz(data.get("url")));
                break;
            case RUN_END:
                endStream();
                running = false;
                // A question that came to nothing ends here; a spoken one once the speaker is done (tick).
                if (phase == LISTENING || phase == THINKING) phase = IDLE;
                break;
            case ERROR:
                String code = nz(data.get("code")), message = nz(data.get("message"));
                // Nothing heard is no failure: the question just came to nothing.
                if (code.equals("stt-no-text-recognized") || code.equals("no_text_recognized")) {
                    phase = IDLE;
                } else {
                    failed(code, message);
                }
                endStream();
                running = false;
                break;
            default:
                break;
        }
        changed();
    }

    public void announce(String media, String text, String preannounce) {
        // assist_satellite.announce: on the media player as an announcement, its words in the popup.
        log.log("assist: announcement");
        if (!text.isEmpty()) {
            answer = text;
            answeredAt = now();
            heard = "";
            phase = ANSWERING;
            if (settings.assistShowPopup()) popup = true;
        }
        String url = !media.isEmpty() ? media : preannounce;
        EspDevice.MediaPlayer.Listener l = player.listener;
        if (url.isEmpty() || l == null) {
            device.announceFinished(false);
            return;
        }
        speakAsked = now();
        speakFrom = 0;
        sawAnnouncing = false;
        announcing = true;
        l.play(url, true);
        startTicker();
        changed();
    }

    private volatile boolean announcing;

    // ----- inside -----

    private void listening() {
        Sounds s = sounds;
        if (phase != LISTENING && s != null && settings.assistWakeSound()) s.wake();
        phase = LISTENING;
        heard = "";
        answer = "";
        speakAsked = speakFrom = closeAt = 0;
        sawAnnouncing = false;
        listenedAt = now();
        if (settings.assistShowPopup()) popup = true;
        startTicker();
    }

    private void startStream() {
        Microphone m = microphone;
        if (m == null || streaming) return;
        streaming = true;
        boolean ok = m.start(new Sink() {
            public void part(byte[] pcm) {
                if (streaming) device.voiceAudio(pcm, false);
            }
        });
        if (!ok) {
            streaming = false;
            device.voiceStop();
            running = false;
            failed("", "The microphone didn't open");
        }
    }

    private void endStream() {
        if (!streaming) return;
        streaming = false;
        Microphone m = microphone;
        if (m != null) m.stop();
        device.voiceAudio(null, true);
    }

    private void speaking(String url) {
        if (url.isEmpty() || speakAsked != 0) return;
        speakAsked = now();
        if (phase == LISTENING || phase == THINKING) {
            phase = ANSWERING;
            answeredAt = now();
        }
        EspDevice.MediaPlayer.Listener l = player.listener;
        if (l != null) l.play(url, true);
    }

    private void failed(String code, String message) {
        log.log("assist: failed " + code + (message.isEmpty() ? "" : " (" + message + ")"));
        answer = message.isEmpty() ? code : message;
        answeredAt = now();
        phase = FAILED;
        speakAsked = speakFrom = 0;
        if (settings.assistShowPopup()) popup = true;
        startTicker();
        changed();
    }

    /** Ten times a second while anything goes on: the sounds, the answer's words, the end of speaking, the popup's time. */
    private synchronized void startTicker() {
        if (ticker != null && ticker.isAlive()) return;
        ticker = new Thread(new Runnable() {
            public void run() {
                while (tick()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
            }
        }, "assist");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** One step; false once nothing goes on any more. */
    private boolean tick() {
        long t = now();
        Sounds s = sounds;
        if (phase == THINKING && s != null && settings.assistThinkingSound() && t - lastThinking >= 900) {
            lastThinking = t;
            s.thinking();
        }
        if (phase == LISTENING && streaming && t - listenedAt > LISTEN_MOST_MS) endStream();
        // The answer is heard once the media player announces it (it buffers first), or a moment after it went there.
        boolean announcingNow = player.playerState() == EspDevice.MediaPlayer.ANNOUNCING;
        if (announcingNow) sawAnnouncing = true;
        if (speakAsked != 0 && speakFrom == 0 && (announcingNow || t - speakAsked >= HEARD_AFTER_MS)) speakFrom = t;
        // Spoken: the player is done with it (or never took it).
        if (speakAsked != 0 && !announcingNow && (sawAnnouncing || t - speakAsked > 8000) && !running) {
            speakAsked = 0;
            if (announcing) {
                announcing = false;
                device.announceFinished(true);
            }
            if (phase == ANSWERING) phase = IDLE;
            if (continueAfter) {
                // Home Assistant asked something back: it listens again, as ESPHome's voice assistant does.
                continueAfter = false;
                changed();
                ask();
                return true;
            }
        }
        if (phase == ANSWERING && speakAsked == 0 && !running && !announcing && answeredAt != 0 && t - answeredAt > 8000) phase = IDLE;
        if (popup && !busy() && phase != LISTENING && closeAt == 0)
            closeAt = t + (answer.isEmpty() && phase != FAILED ? POPUP_EMPTY_MS : POPUP_AFTER_MS);
        if (popup && closeAt != 0 && t >= closeAt) {
            popup = false;
            closeAt = 0;
        }
        changed();
        return busy() || popup || speakAsked != 0 || replyFresh();
    }

    private void changed() {
        Runnable r = changed;
        if (r != null) r.run();
    }

    private static long now() { return System.nanoTime() / 1000000L; }

    private static String nz(String s) { return s == null ? "" : s; }
}
