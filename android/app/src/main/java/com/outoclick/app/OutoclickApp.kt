package com.outoclick.app

import android.app.Application
import android.content.Context
import com.outoclick.app.data.AppRepository
import com.outoclick.app.engine.SessionManager
import com.outoclick.app.engine.TargetAppResolver

/**
 * 极简依赖图。进程内单例；Activity/Service 都从这里取。
 */
object AppGraph {
    lateinit var repo: AppRepository
        private set

    lateinit var resolver: TargetAppResolver
        private set

    lateinit var sessions: SessionManager
        private set

    fun init(context: Context) {
        if (::repo.isInitialized) return
        val app = context.applicationContext
        repo = AppRepository(app)
        resolver = TargetAppResolver(app)
        sessions = SessionManager(app, repo)
        // 进程刚启动，任何非终态会话都不可能还活着。
        sessions.reconcileOnStartup()
    }
}

class OutoclickApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
