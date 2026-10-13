# WorkManager startet die Klasse über den gespeicherten Namen.
-keep class de.dgstudios.iptvstream.core.data.EpgRefreshWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Retrofit-Interface und Room werden über deren eigene Consumer-Regeln abgedeckt.
-keep interface de.dgstudios.iptvstream.core.data.remote.HttpService { *; }
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# DefaultRenderersFactory lädt den FFmpeg-Renderer über den Klassennamen.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder { *; }
