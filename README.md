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
| **Four tabs** | **Play** (you vs Stockfish), **Two Player** (Tabletop: phone flat between two players with Black's pieces drawn upside down for the opposite player; Pass & Play: the board turns each move; Fixed: White at the bottom), **Import** (FEN / PGN / screenshot), **Review** (your saved games). All four use the same board workspace. |
| **Thumb-zone layout** | Fixed layout from top to bottom: header (48dp), move strip (40dp), free space, full-width board, action bar (48dp), mode bar (44dp), tabs. The board sits low enough to reach with one thumb, and pop-ups float over the free space, so the board never moves. |
| **Move navigation** | `|<` `<` `>` `>|` plus a move strip you can tap to jump to any move. If you go back and play a different move, the later moves are dropped and the game continues from your new move (Stockfish replies in Play). |
| **Best move + why / Live coach** | A floating card you can close with × or by swiping. It shows the best move as a blue arrow, the eval, tactic/position tags, 2–3 reasons, the expected continuation and the 2nd-best move. Live coach keeps analysing while the card is hidden, and the "Show coach" pill brings it back. |
| **Read aloud** | The speaker button reads the card using the phone's offline text-to-speech, with chess notation spoken naturally ("Bxf7+" is read as "Bishop takes f7, check"). It only speaks when you tap it. |
| **Board sounds** | Move, capture, castle, check and game-end sounds play through one `SoundPool` that never takes audio focus, so music and speech are not interrupted on every move. |
| **Move quality** | Each move gets a badge: Brilliant `!!`, Great `!`, Best `★`, Good, Inaccuracy `?!`, Mistake `?` or Blunder `??`. It is based on Stockfish MultiPV-2 centipawn differences and shown on the board and in the move strip. |
| **Summary pop-up** | Accuracy for each side and a count of each move quality. Tap a count to jump to that move, with the move you played and the best move both drawn as arrows. |
| **Board themes** | Classic Green, Tournament Blue, Walnut Wood, Midnight Slate and Royal Violet. Pick one from ⋮ → Board theme. The choice applies to every tab and is saved with DataStore. |
| **Review history** | Finished and reviewed games are stored in a local Room database with accuracy and badges, and stay until you delete them or clear the app's storage. |
| **Optional cloud backup** | Google Sign-In with Firebase (free Spark tier). This is **off unless you add `app/google-services.json`**. Without that file the APK has no INTERNET permission. See *Cloud backup setup* below. |
| **Elo 800 to 3200** | Stockfish `UCI_Elo` covers 1320–3190, `Skill Level` is used below that, and the far right of the slider plays at full strength. |

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
The release build is signed with the committed sideload key (see *Signing*).

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

## Cloud backup setup (optional)

1. In the [Firebase console](https://console.firebase.google.com), create a project and stay on the free Spark plan.
2. Add an Android app with package `com.mrchess.app` and the SHA-1 of the signing key:
   `D4:36:F4:E5:35:57:2E:7C:80:B3:D3:02:3A:32:A1:CD:55:A6:82:0D`
3. Under **Authentication → Sign-in method**, enable **Google**.
4. Create a **Firestore** database with these rules:
   ```
   rules_version = '2';
   service cloud.firestore { match /databases/{db}/documents {
     match /users/{uid}/games/{id} { allow read, write: if request.auth != null && request.auth.uid == uid; }
   } }
   ```
5. Download `google-services.json` again (after enabling Google sign-in) and commit it as `app/google-services.json`.
   The next build includes the `:cloudsync` module and the INTERNET permission, and the Review tab
   shows "Sign in with Google to sync history".

## Signing

`app/mrchess-sideload.keystore` is committed so every CI build is signed with the same key and
installs as an update over the previous one. Because the repo is public, anyone could sign an APK
with this key. That is fine for personal sideloading. For a Play Store release, set
`MRCHESS_KEYSTORE`, `MRCHESS_KEYSTORE_PASSWORD`, `MRCHESS_KEY_ALIAS` and `MRCHESS_KEY_PASSWORD` to
your own private key.

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
