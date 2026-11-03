package com.senzaproject.studio

import android.app.Application
import com.senzaproject.studio.audio.engine.AudioEngine
import com.senzaproject.studio.audio.engine.NoOpAudioEngine

/** Manual dependency container (no DI framework: fewer dependencies, easier to audit). */
class AppContainer {
    val engine: AudioEngine = NoOpAudioEngine()
}

class SenzaStudioApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
