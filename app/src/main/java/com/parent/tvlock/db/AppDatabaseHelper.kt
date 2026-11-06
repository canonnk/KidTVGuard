package com.parent.tvlock.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.parent.tvlock.util.L

/**
 * 原生 SQLite 数据库统一管理帮助类（单例）
 */
class AppDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "tv_lock.db"
        const val DATABASE_VERSION = 3

        // 表名定义
        const val TABLE_QUESTIONS = "questions"
        const val TABLE_WRONG_QUESTIONS = "wrong_questions"
        const val TABLE_ANSWER_RECORDS = "answer_records"
        const val TABLE_APP_USAGE_RECORDS = "app_usage_records"

        @Volatile
        private var instance: AppDatabaseHelper? = null

        fun getInstance(context: Context): AppDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AppDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 开启外键约束支持
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        L.i("Initializing database tables for $DATABASE_NAME...")

        // 1. 题目表 (questions)
        val sqlCreateQuestions = """
            CREATE TABLE $TABLE_QUESTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL,
                questionType TEXT NOT NULL DEFAULT 'CHOICE',
                questionText TEXT NOT NULL,
                questionImageUrl TEXT,
                localImagePath TEXT,
                optionA TEXT,
                optionB TEXT,
                optionC TEXT,
                optionD TEXT,
                optionAImageUrl TEXT,
                optionBImageUrl TEXT,
                optionCImageUrl TEXT,
                optionDImageUrl TEXT,
                correctAnswer TEXT NOT NULL,
                acceptableAnswers TEXT,
                explanation TEXT,
                isAnsweredCorrectly INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()
        db.execSQL(sqlCreateQuestions)
        db.execSQL("CREATE INDEX idx_questions_correct ON $TABLE_QUESTIONS(isAnsweredCorrectly);")

        // 2. 错题本表 (wrong_questions)
        val sqlCreateWrongQuestions = """
            CREATE TABLE $TABLE_WRONG_QUESTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                questionId INTEGER NOT NULL,
                wrongCount INTEGER NOT NULL DEFAULT 1,
                lastWrongAnswer TEXT NOT NULL,
                lastWrongTime INTEGER NOT NULL,
                isMastered INTEGER NOT NULL DEFAULT 0,
                masteredTime INTEGER,
                FOREIGN KEY (questionId) REFERENCES $TABLE_QUESTIONS(id) ON DELETE CASCADE
            );
        """.trimIndent()
        db.execSQL(sqlCreateWrongQuestions)
        db.execSQL("CREATE UNIQUE INDEX idx_wrong_question_id ON $TABLE_WRONG_QUESTIONS(questionId);")
        db.execSQL("CREATE INDEX idx_wrong_mastered ON $TABLE_WRONG_QUESTIONS(isMastered);")

        // 3. 答题记录流水表 (answer_records)
        val sqlCreateAnswerRecords = """
            CREATE TABLE $TABLE_ANSWER_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                questionId INTEGER NOT NULL,
                selectedAnswer TEXT NOT NULL,
                isCorrect INTEGER NOT NULL,
                answerTime INTEGER NOT NULL,
                timeSpentSeconds INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (questionId) REFERENCES $TABLE_QUESTIONS(id) ON DELETE CASCADE
            );
        """.trimIndent()
        db.execSQL(sqlCreateAnswerRecords)
        db.execSQL("CREATE INDEX idx_answer_records_time ON $TABLE_ANSWER_RECORDS(answerTime);")

        // 4. 电视应用活动记录表 (app_usage_records)
        val sqlCreateAppUsage = """
            CREATE TABLE $TABLE_APP_USAGE_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                packageName TEXT NOT NULL,
                appName TEXT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                recordDate TEXT NOT NULL
            );
        """.trimIndent()
        db.execSQL(sqlCreateAppUsage)
        db.execSQL("CREATE INDEX idx_app_usage_date ON $TABLE_APP_USAGE_RECORDS(recordDate);")

        L.i("Database tables created successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        L.i("Upgrading database from version $oldVersion to $newVersion")
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN optionAImageUrl TEXT;")
                db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN optionBImageUrl TEXT;")
                db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN optionCImageUrl TEXT;")
                db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN optionDImageUrl TEXT;")
                L.i("Successfully upgraded database to v2 (added option image columns).")
            } catch (e: Exception) {
                L.e("Failed to upgrade database to v2", tr = e)
            }
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN questionType TEXT NOT NULL DEFAULT 'CHOICE';")
                db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN acceptableAnswers TEXT;")
                L.i("Successfully upgraded database to v3 (added questionType and acceptableAnswers).")
            } catch (e: Exception) {
                L.e("Failed to upgrade database to v3", tr = e)
            }
        }
    }
}
