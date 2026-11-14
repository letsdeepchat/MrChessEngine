# MrChess Trainer

An offline Android chess trainer. You play Stockfish at any strength from beginner up to full
strength (3200+), and you can ask for the best move together with a plain-language reason. It
also does post-game review and can import a position from FEN, PGN, or a screenshot.

The app has **no INTERNET permission**. The engine, the neural networks, and the screenshot
recogniser all run on the device.

## Download

**[⬇ MrChessTrainer.apk](https://github.com/letsdeepchat/MrChessEngine/releases/latest/download/MrChessTrainer.apk)** for 64-bit Android phones (Android 8+). Open it on your phone and allow installing from this source. Every push to `main` rebuilds it.

## Features

| | |
|---|---|
| **Play vs engine** | Elo slider from 800 to 3200. 1320–3190 uses Stockfish `UCI_Elo`, below that it uses `Skill Level`, and the far right turns the limiter off (full NNUE strength). You can play either colour, undo, resign, and promote. |
| **Best move + why** | The hint always runs at full strength with MultiPV 2. It shows the best move as an arrow, the eval, a 3-ply line, the second-best move and how much worse it is, and the reasons (see below). |
| **Live coach / Analysis mode** | Analysis mode lets you move both sides. With *Live coach* on, every position is analysed automatically. |
| **Import** | Paste a FEN or PGN, or share/pick a screenshot. The screenshot goes through OpenCV board detection and a 13-class INT8 TFLite tile classifier, then you confirm side-to-move and orientation before analysing or playing from that position. |
| **Game review** | Every finished game is saved as PGN. The review gives Lichess-style win% loss per move (inaccuracy/mistake/blunder at 10/20/30), the Lichess accuracy formula, and for each mistake: the better move, why it's better, and the refutation of the move that was played. |

### How explanations work (`chesscore/.../explain/MoveExplainer.kt`)
Stockfish picks the move, and a rule layer explains it by looking at the board before and after
the move and along the PV:

* **Mate**: forced mate in N, with the line.
* **Material**: captures (free piece, or winning the exchange) and material gained along the first 4 plies of the PV.
* **Tactics**: forks, absolute and relative pins, skewers, discovered attacks and checks, promotions.
* **Defence**: escaping check, saving an attacked piece, and parrying the opponent's biggest threat (found with a null move: "what would they do if you passed?").
* **"Only strong move"** when the MultiPV-2 alternative is more than 150 cp worse. When the two are about equal, it says so.
* **Positional** (only when nothing tactical applies): development, centre control, open/half-open files, passed pawns, king activity in the endgame, mobility.

## Project layout

```
chesscore/      Pure Kotlin/JVM, no Android deps. Unit-tested on desktop against real Stockfish.
  rules/        Board, legal move gen (perft-verified), FEN, SAN, PGN
  engine/       UciEngine (coroutines, cancel-safe), EngineStrength, Score/MultiPV parsing
  explain/      MoveExplainer
  review/       GameReviewer (win%, accuracy, classifications)
  Coach.kt      Facade the app uses: opponentMove / hint / review
app/            Android: Compose UI, EngineProvider, GameStore, vision/ImageToFen (OpenCV + TFLite)
ml_training/    train_piece_classifier.py (synthetic tiles -> INT8 TFLite), image_to_fen.py (desktop reference + eval)
scripts/        build_stockfish_android.sh (NDK build -> jniLibs/arm64-v8a/libstockfish.so)
```

The engine runs as a separate UCI process. It is packaged as `libstockfish.so` so Android
installs it executable in `nativeLibraryDir`, and the manifest sets `extractNativeLibs=true` with
legacy JNI packaging. Stockfish's own Makefile (`COMP=ndk`) builds it and embeds the official
NNUE nets.

I did not use chesslib: it is only published on JitPack, which is a fragile dependency for an
offline-first app. The rules engine lives in `chesscore/rules` and passes the standard perft
suites instead.

## Build

Requirements: JDK 17, Android SDK 35, Android NDK r26 or newer, `make`, `curl`.

```bash
# 1. Engine (downloads Stockfish 17.1 source + NNUE nets, builds for arm64)
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
scripts/build_stockfish_android.sh            # SF_ARCH=armv8-dotprod for ~15% more speed on 2018+ phones

# 2. APK
./gradlew :app:assembleRelease                 # app/build/outputs/apk/release/app-release.apk
```

The GitHub Actions workflow (`.github/workflows/android.yml`) runs the same steps and uploads the
APK as an artifact. Push the repo and you get an installable APK without a local Android setup.
The release build is signed with the debug key so you can sideload it directly. Swap in your own
key before publishing.

**APK size.** The budget is under 100 MB. The Stockfish 17.1 binary with both embedded nets is
the bulk of it, OpenCV adds about 20 MB for arm64 and TFLite about 3 MB. If you need it smaller,
`SF_VERSION=sf_16.1` uses smaller nets.

### Core tests (no Android SDK needed)
```bash
sudo apt install stockfish   # or set STOCKFISH_PATH
./gradlew :chesscore:test -PcoreOnly=true
```
There are 20 tests. They cover perft on 5 reference positions, FEN/SAN/PGN round-trips,
mate/fork/pin/skewer/free-capture/saving-a-piece explanations against real Stockfish,
limited-strength play, search cancellation safety, and a review that flags the scholar's-mate
blunder with its refutation.

## Screenshot recognition model

`app/src/main/assets/piece_classifier_int8.tflite` is already trained, using synthetic data only.
To retrain it or adapt it to the board/piece themes you actually use:

```bash
cd ml_training && pip install -r requirements.txt
python train_piece_classifier.py --samples-per-class 6000 --epochs 15 \
    --piece-sets ./piece_sets      # optional: folders of wK.png … bP.png piece sets
    --real-dir ./real_tiles        # optional: hand-labelled tiles, <dir>/w_K/*.png etc.
python image_to_fen.py --synthetic 100        # tile accuracy on rendered random boards
python image_to_fen.py my_screenshot.png      # FEN for a real image
```
The tiles are 64×64 RGB, the input is quantised to int8 (scale about 1/255, zero point -128),
and the output is int8 softmax over `empty P N B R Q K p n b r q k`. Adding the PNG piece sets of
the apps you take screenshots from is the single biggest accuracy gain on real images.

## Fair play

This is a training tool. It deliberately has no overlay over other apps, no screen capture, and
no background recognition. Using engine help during rated online games breaks every site's
rules. Use it on your own games after they finish, and for practice against the built-in engine.
