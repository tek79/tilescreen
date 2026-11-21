package app.tilescreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import app.tilescreen.core.EspDevice;

/**
 * The tablet's speaker for Home Assistant: ESPHome's media player (EspDevice.MediaPlayer), played with Android's own
 * MediaPlayer, so any link Home Assistant hands over (text to speech, a chime, a radio stream, a file) plays with the
 * tablet's decoders and nothing is bundled. Music and announcements each have a player of their own, as a Tessera P4
 * screen's two pipelines: an announcement pauses the music and the music goes on after it.
 *
 * The volume is Android's media volume, so the tablet's own keys and Home Assistant's slider move the same thing; mute
 * silences the players and leaves the volume where it was. A call or the doorbell's voice takes the speaker
 * (hold): the music pauses and doesn't start again by itself, as on a Tessera screen.
 */
final class Player implements EspDevice.MediaPlayer.Listener {
    private final Context context;
    private final EspDevice.MediaPlayer entity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final SharedPreferences prefs;
    private MediaPlayer music, announcement;
    private boolean musicPlaying, musicPaused, resumeAfter, muted, held;
    private float volume;

    Player(Context context, EspDevice.MediaPlayer entity, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.entity = entity;
        this.prefs = prefs;
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.volume = currentVolume();
        this.muted = prefs.getBoolean("media_muted", false);
        entity.listener = this;
        report();
    }

    // ----- from Home Assistant, on its connection's thread -----

    public void command(final int command) {
        main.post(new Runnable() {
            public void run() { onCommand(command); }
        });
    }

    public void volume(final float v) {
        main.post(new Runnable() {
            public void run() { setVolume(v); }
        });
    }

    public void play(final String url, final boolean isAnnouncement) {
        main.post(new Runnable() {
            public void run() {
                if (isAnnouncement) announce(url);
                else playMusic(url);
            }
        });
    }

    // ----- on the main thread -----

    private void onCommand(int c) {
        switch (c) {
            case EspDevice.MediaPlayer.PLAY:
                if (music != null && musicPaused && !held) {
                    music.start();
                    musicPaused = false;
                    musicPlaying = true;
                }
                break;
            case EspDevice.MediaPlayer.PAUSE:
                pauseMusic();
                break;
            case EspDevice.MediaPlayer.TOGGLE:
                if (musicPlaying) pauseMusic();
                else onCommand(EspDevice.MediaPlayer.PLAY);
                break;
            case EspDevice.MediaPlayer.STOP:
            case EspDevice.MediaPlayer.TURN_OFF:
                stopAll();
                break;
            case EspDevice.MediaPlayer.MUTE:
                muted = true;
                prefs.edit().putBoolean("media_muted", true).apply();
                applyLevel();
                break;
            case EspDevice.MediaPlayer.UNMUTE:
                muted = false;
                prefs.edit().putBoolean("media_muted", false).apply();
                applyLevel();
                break;
            case EspDevice.MediaPlayer.VOLUME_UP:
                setVolume(Math.min(1f, currentVolume() + 0.1f));
                return;
            case EspDevice.MediaPlayer.VOLUME_DOWN:
                setVolume(Math.max(0f, currentVolume() - 0.1f));
                return;
            default:
                break;
        }
        report();
    }

    private void setVolume(float v) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
        volume = v;
        report();
    }

    private float currentVolume() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0.5f : audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private void applyLevel() {
        float l = muted ? 0f : 1f;
        if (music != null) music.setVolume(l, l);
        if (announcement != null) announcement.setVolume(l, l);
    }

    private MediaPlayer make() {
        MediaPlayer p = new MediaPlayer();
        p.setAudioStreamType(AudioManager.STREAM_MUSIC);
        p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        return p;
    }

    private void playMusic(String url) {
        releaseMusic();
        musicPaused = false;
        if (held) {
            App.get().log("media: " + shortUrl(url) + " waits, the speaker is in use");
            report();
            return;
        }
        final MediaPlayer p = make();
        music = p;
        try {
            p.setDataSource(url);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    if (mp != music) return;
                    applyLevel();
                    // An announcement that plays meanwhile keeps the speaker: the music starts after it.
                    if (announcement != null) {
                        resumeAfter = true;
                        musicPaused = true;
                    } else {
                        mp.start();
                        musicPlaying = true;
                    }
                    report();
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    if (mp != music) return;
                    releaseMusic();
                    report();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    App.get().log("media: couldn't play (" + what + "/" + extra + ")");
                    if (mp == music) releaseMusic();
                    report();
                    return true;
                }
            });
            p.prepareAsync();
            App.get().log("media: playing " + shortUrl(url));
        } catch (Exception e) {
            App.get().log("media: couldn't play " + shortUrl(url) + ": " + e);
            releaseMusic();
        }
        report();
    }

    private void announce(String url) {
        if (announcement != null) {
            announcement.release();
            announcement = null;
        }
        if (musicPlaying && music != null) {
            music.pause();
            musicPlaying = false;
            musicPaused = true;
            resumeAfter = true;
        }
        final MediaPlayer p = make();
        announcement = p;
        try {
            p.setDataSource(url);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    if (mp != announcement) return;
                    applyLevel();
                    mp.start();
                }
            });
            MediaPlayer.OnCompletionListener done = new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { announcementDone(mp); }
            };
            p.setOnCompletionListener(done);
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    App.get().log("media: couldn't announce (" + what + "/" + extra + ")");
                    announcementDone(mp);
                    return true;
                }
            });
            p.prepareAsync();
            App.get().log("media: announcing " + shortUrl(url));
        } catch (Exception e) {
            App.get().log("media: couldn't announce " + shortUrl(url) + ": " + e);
            announcementDone(p);
        }
        report();
    }

    private void announcementDone(MediaPlayer mp) {
        if (mp != announcement) return;
        announcement.release();
        announcement = null;
        if (resumeAfter && music != null && musicPaused && !held) {
            music.start();
            musicPaused = false;
            musicPlaying = true;
        }
        resumeAfter = false;
        report();
    }

    private void pauseMusic() {
        if (music != null && musicPlaying) {
            music.pause();
            musicPlaying = false;
            musicPaused = true;
        }
        resumeAfter = false;
    }

    private void stopAll() {
        releaseMusic();
        if (announcement != null) {
            announcement.release();
            announcement = null;
        }
        resumeAfter = false;
    }

    private void releaseMusic() {
        if (music != null) {
            try { music.release(); } catch (Exception ignored) {}
        }
        music = null;
        musicPlaying = musicPaused = false;
    }

    /**
     * A call or the doorbell's voice takes the speaker: music pauses and stays paused, an announcement stops. Let go,
     * nothing starts again by itself, as on a Tessera screen (audio_owner.h).
     */
    void hold(final boolean on) {
        main.post(new Runnable() {
            public void run() {
                held = on;
                if (on) {
                    pauseMusic();
                    if (announcement != null) {
                        announcement.release();
                        announcement = null;
                    }
                }
                report();
            }
        });
    }

    private void report() {
        int state = announcement != null ? EspDevice.MediaPlayer.ANNOUNCING
                : musicPlaying ? EspDevice.MediaPlayer.PLAYING
                : musicPaused ? EspDevice.MediaPlayer.PAUSED : EspDevice.MediaPlayer.IDLE;
        entity.set(state, volume, muted);
    }

    /** A link without its query, which may carry a token: only where it came from goes in the log. */
    private static String shortUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String s = q < 0 ? url : url.substring(0, q);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
