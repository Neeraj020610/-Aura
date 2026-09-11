package com.aura.client

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.abs

class SnakeGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }
    enum class GameState { READY, RUNNING, PAUSED, GAME_OVER }

    private val snake = mutableListOf<Point>()
    private var food = Point(5, 5)
    private var direction = Direction.RIGHT
    private var nextDirection = Direction.RIGHT
    var gameState = GameState.READY
        private set

    var score = 0
        private set
    var highScore = 0
        private set

    private var gridSize = 20
    private var cellWidth = 0f
    private var cellHeight = 0f

    private val random = Random()
    private val handler = Handler(Looper.getMainLooper())
    private var gameSpeed = 160L // ms per frame

    // Paint objects
    private val bgPaint = Paint().apply { color = Color.parseColor("#050811") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#0d1527")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val snakeHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00f3ff")
        setShadowLayer(14f, 0f, 0f, Color.parseColor("#00f3ff"))
    }
    private val snakeBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b4d8")
    }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff007f")
        setShadowLayer(16f, 0f, 0f, Color.parseColor("#ff007f"))
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94a3b8")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    var onScoreChangeListener: ((score: Int, highScore: Int) -> Unit)? = null
    var onGameStateChangeListener: ((state: GameState) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y

            if (abs(diffX) > abs(diffY)) {
                if (abs(diffX) > 50) {
                    if (diffX > 0) setMoveDirection(Direction.RIGHT)
                    else setMoveDirection(Direction.LEFT)
                }
            } else {
                if (abs(diffY) > 50) {
                    if (diffY > 0) setMoveDirection(Direction.DOWN)
                    else setMoveDirection(Direction.UP)
                }
            }
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    })

    private val gameLoopRunnable = object : Runnable {
        override fun run() {
            if (gameState == GameState.RUNNING) {
                updateGame()
                invalidate()
                handler.postDelayed(this, gameSpeed)
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val prefs = context.getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
        highScore = prefs.getInt("snake_high_score", 0)
        resetGame()
    }

    fun startGame() {
        if (gameState == GameState.READY || gameState == GameState.GAME_OVER) {
            resetGame()
        }
        gameState = GameState.RUNNING
        onGameStateChangeListener?.invoke(gameState)
        handler.removeCallbacks(gameLoopRunnable)
        handler.post(gameLoopRunnable)
        vibrate(30)
    }

    fun pauseGame() {
        if (gameState == GameState.RUNNING) {
            gameState = GameState.PAUSED
            onGameStateChangeListener?.invoke(gameState)
            handler.removeCallbacks(gameLoopRunnable)
            invalidate()
        } else if (gameState == GameState.PAUSED) {
            gameState = GameState.RUNNING
            onGameStateChangeListener?.invoke(gameState)
            handler.post(gameLoopRunnable)
        }
    }

    fun resetGame() {
        snake.clear()
        val startX = gridSize / 2
        val startY = gridSize / 2
        snake.add(Point(startX, startY))
        snake.add(Point(startX - 1, startY))
        snake.add(Point(startX - 2, startY))

        direction = Direction.RIGHT
        nextDirection = Direction.RIGHT
        score = 0
        gameSpeed = 160L
        spawnFood()
        onScoreChangeListener?.invoke(score, highScore)
        invalidate()
    }

    fun setMoveDirection(newDir: Direction) {
        if (gameState == GameState.READY) {
            startGame()
        }
        if (gameState != GameState.RUNNING) return

        // Prevent 180-degree reversal
        if ((direction == Direction.UP && newDir != Direction.DOWN) ||
            (direction == Direction.DOWN && newDir != Direction.UP) ||
            (direction == Direction.LEFT && newDir != Direction.RIGHT) ||
            (direction == Direction.RIGHT && newDir != Direction.LEFT)
        ) {
            nextDirection = newDir
        }
    }

    private fun spawnFood() {
        var newFood: Point
        do {
            newFood = Point(random.nextInt(gridSize), random.nextInt(gridSize))
        } while (snake.contains(newFood))
        food = newFood
    }

    private fun updateGame() {
        direction = nextDirection
        val head = snake[0]
        val newHead = when (direction) {
            Direction.UP -> Point(head.x, head.y - 1)
            Direction.DOWN -> Point(head.x, head.y + 1)
            Direction.LEFT -> Point(head.x - 1, head.y)
            Direction.RIGHT -> Point(head.x + 1, head.y)
        }

        // Check wall collision
        if (newHead.x < 0 || newHead.x >= gridSize || newHead.y < 0 || newHead.y >= gridSize) {
            gameOver()
            return
        }

        // Check self collision
        if (snake.contains(newHead)) {
            gameOver()
            return
        }

        snake.add(0, newHead)

        // Check food collision
        if (newHead == food) {
            score += 10
            if (score > highScore) {
                highScore = score
                val prefs = context.getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("snake_high_score", highScore).apply()
            }
            onScoreChangeListener?.invoke(score, highScore)
            vibrate(50)

            // Speed up slightly as snake grows
            if (gameSpeed > 80L && score % 40 == 0) {
                gameSpeed -= 10L
            }

            spawnFood()
        } else {
            snake.removeAt(snake.size - 1)
        }
    }

    private fun gameOver() {
        gameState = GameState.GAME_OVER
        onGameStateChangeListener?.invoke(gameState)
        handler.removeCallbacks(gameLoopRunnable)
        vibrate(250)
        invalidate()
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (e: Exception) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = w.coerceAtMost(h)
        cellWidth = size.toFloat() / gridSize
        cellHeight = size.toFloat() / gridSize
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = width.coerceAtMost(height).toFloat()
        val offsetX = (width - size) / 2f
        val offsetY = (height - size) / 2f

        // Draw Background
        canvas.drawRect(offsetX, offsetY, offsetX + size, offsetY + size, bgPaint)

        // Draw Grid lines
        for (i in 0..gridSize) {
            val pos = i * cellWidth
            canvas.drawLine(offsetX + pos, offsetY, offsetX + pos, offsetY + size, gridPaint)
            canvas.drawLine(offsetX, offsetY + pos, offsetX + size, offsetY + pos, gridPaint)
        }

        // Draw Food
        val foodX = offsetX + food.x * cellWidth + cellWidth / 2f
        val foodY = offsetY + food.y * cellHeight + cellHeight / 2f
        canvas.drawCircle(foodX, foodY, cellWidth * 0.42f, foodPaint)

        // Draw Snake
        for (i in 0 until snake.size) {
            val part = snake[i]
            val px = offsetX + part.x * cellWidth
            val py = offsetY + part.y * cellHeight
            val rect = RectF(px + 2, py + 2, px + cellWidth - 2, py + cellHeight - 2)

            if (i == 0) {
                // Head
                canvas.drawRoundRect(rect, 8f, 8f, snakeHeadPaint)
            } else {
                // Body
                canvas.drawRoundRect(rect, 6f, 6f, snakeBodyPaint)
            }
        }

        // Overlay text for Ready / Game Over / Paused
        if (gameState == GameState.READY) {
            canvas.drawText("TAP START TO PLAY", offsetX + size / 2f, offsetY + size / 2f - 20, textPaint)
            canvas.drawText("Swipe or use D-Pad to control", offsetX + size / 2f, offsetY + size / 2f + 30, subTextPaint)
        } else if (gameState == GameState.GAME_OVER) {
            textPaint.color = Color.parseColor("#ff0055")
            canvas.drawText("GAME OVER", offsetX + size / 2f, offsetY + size / 2f - 30, textPaint)
            textPaint.color = Color.WHITE
            canvas.drawText("Score: $score  |  Best: $highScore", offsetX + size / 2f, offsetY + size / 2f + 20, subTextPaint)
            canvas.drawText("Tap Restart to Play Again", offsetX + size / 2f, offsetY + size / 2f + 65, subTextPaint)
        } else if (gameState == GameState.PAUSED) {
            canvas.drawText("GAME PAUSED", offsetX + size / 2f, offsetY + size / 2f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) {
            if (gameState == GameState.READY || gameState == GameState.GAME_OVER) {
                startGame()
            }
        }
        return true
    }
}
