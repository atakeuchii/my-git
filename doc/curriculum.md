# Git 内部実装 自作カリキュラム（Clojure / 12日）

> **ゴール**: Git の中身（オブジェクトモデル〜マージ）を Clojure で実装し、「毎日使っている git が内部で実際に何をしているか」を手で理解する。`init / add / commit / log / diff / branch / checkout / status / merge` ができるミニ Git を作る。
> **対象**: git を日常的に使っているが、低レベル（プランビング）の中身は外から使う側だった DB / バックエンドエンジニア。
> **想定時間**: 平日 2〜3h + 週末多め、ゆるく12日。早ければ10日でも回せる構成。

---

## なぜこれをやるのか（最初に読む）

普段あなたが「使う側」にいる git は、内部でだいたい次のことをやっている：

1. ファイルの中身を **SHA-1 でアドレス化して圧縮保存**する（content-addressable なオブジェクトストア）
2. ディレクトリ構造を **tree オブジェクト**で表す
3. スナップショットを **commit オブジェクト**（tree + 親 + メタ情報）で表す
4. ブランチは **commit hash を指すただのファイル**（ref）
5. 作業ディレクトリとリポジトリの間に **index（ステージング）**という中間状態がある
6. マージは **共通祖先を見つけて 3-way merge** する

この6つを自分で書くと、「なぜ `git add` と `git commit` は別なのか」「`detached HEAD` とは何か」「なぜ commit hash は中身が同じなら一致するのか」「なぜマージでコンフリクトが起きるのか」が腹落ちする。**低レベルを知らずに使っている部分こそ、シニアとそれ以外を分ける。**

### この題材の最大の旨味：本物の git が参照オラクルになる

LSM で「自作エンジン vs 素の `sorted-map`」、正規表現で「自作 vs `java.util.regex`」を参照オラクルにしたのと同じことが、Git では**本物の git そのもの**で成立する。

- 自作エンジンが作った `.git/objects/...` を、本物の `git cat-file -p <hash>` が読める
- 自作エンジンで `commit` したリポジトリで、本物の `git log` が履歴を表示できる
- 逆に、本物の git が作ったオブジェクトを自作エンジンが読める

**「自作で書いた `.git` を本物 git が解釈できる」を Day 1 から満たし続ける**のが、このカリキュラムの北極星。検証手段が最初から OS に入っている、という他にない強みがある。

### Clojure でやる旨味（意識して使う）

- **Git のオブジェクトモデルは永続データ構造そのもの**。blob も tree も commit も一度作ったら不変で、変更は「新しいオブジェクトを作って ref を差し替える」だけ。これは Clojure の `assoc` が新しい構造を返すのと**まったく同じ思想**。あなたが日常書いているコードの哲学が、そのまま Git の設計として現れる。
- **オブジェクトストア = KV ストア**。SHA-1 をキー、zlib 圧縮バイト列を値。あなたが LSM で握った「キーで引く・イミュータブルに保存する」感覚が初日から直結する。
- **永続化 = `java.nio` / `java.util.zip` / `java.security`** にフルアクセス。SHA-1 は `MessageDigest`、zlib は `Deflater`/`Inflater`、ファイル I/O は `java.nio`。JVM の資産がそのまま使える。
- 唯一の摩擦は index のバイナリ形式（Day 4）と tree の生バイト（Day 2）。ここは `DataOutputStream` / `ByteBuffer` 直叩きで丁寧に。

---

## 全体アーキテクチャ

```
       ファイルの中身（バイト列）
             │  ① SHA-1 + zlib 圧縮 → .git/objects/ab/cdef...
             ▼
        ┌─────────────┐
        │   blob      │  ← ファイル1個の中身。KVストアそのもの
        └─────────────┘
             │  ② 名前→hash の一覧
             ▼
        ┌─────────────┐
        │   tree      │  ← ディレクトリ。blob と別 tree を指す再帰構造
        └─────────────┘
             │  ③ tree スナップショット + 親 + author/message
             ▼
        ┌─────────────┐
        │   commit    │  ← 履歴の1点。親ポインタで繋がる
        └─────────────┘
             │  ④ commit hash を指すポインタ
             ▼
     refs/heads/main, HEAD  ← ブランチ = ただのファイル
             ▲
             │  ⑤ add で index に積む → commit で tree 化
        ┌─────────────┐
        │   index     │  ← 作業ディレクトリ ↔ リポジトリの中間状態
        └─────────────┘
             │  ⑥ 親を辿る / tree を比較 / 共通祖先で 3-way merge
             ▼
        log / diff / merge

オブジェクトの4種類: blob（中身）/ tree（ディレクトリ）/ commit（履歴）/ tag（名前・任意）
参照の探索: HEAD → refs/heads/<branch> → commit → tree → blob
```

---

## フェーズ構成

| フェーズ | 日 | テーマ | git のレイヤ |
|---|---|---|---|
| 0. 準備 | Day 0 | 環境構築・`.git` 探検・スコープ決定 | — |
| 1. プランビング（低レベル） | Day 1–4 | オブジェクトストア → tree → commit/refs → index | 内部を直接触る |
| 2. ポーセリン（高レベル） | Day 5–9 | add/commit → log/diff → branch → checkout → status | 日常コマンド |
| 3. マージ & 仕上げ | Day 10–12 | 相互運用検証 → 3-way merge → README | 山場と締め |

各日に **目標 / やること / 概念 / Clojureヒント / チェックポイント**。チェックポイントが「その日の完了条件（DoD）」。

---

## Day 0 — 準備・`.git` 探検・スコープ決定

**目標**: Git の全体像を頭に入れ、実物の `.git` を覗いて手を動かす土台を作る。

**やること**
- `project.clj` でプロジェクト作成（`my-git` など）。エントリは1つの名前空間でOK（後で分割）。
- REPL 駆動の準備（普段の CIDER / Calva 環境）。依存は最初は不要（SHA-1・zlib は JVM 標準）。property test 用に後で `org.clojure/test.check` を足す。
- **実物の `.git` を手で探検する**（これが一番効くインプット）：
  ```
  mkdir sandbox && cd sandbox && git init
  echo "hello" > a.txt && git add a.txt && git commit -m "first"
  ls -F .git/                      # objects/ refs/ HEAD config ...
  find .git/objects -type f        # loose object が3つできている
  git cat-file -t <hash>           # blob / tree / commit
  git cat-file -p <hash>           # 中身を見る
  cat .git/HEAD                    # ref: refs/heads/main
  cat .git/refs/heads/main         # commit hash
  ```

**概念（読む）**
- **Pro Git 第10章 "Git Internals"** の前半（Plumbing and Porcelain / Git Objects / Git References）。**このカリキュラムの背骨**なので最優先。
- Git は「VCS の皮をかぶった content-addressable filesystem」だという一文を腹落ちさせる。

**スコープの決定（唯一の入口の設計判断）**
- ハッシュは **SHA-1**（git デフォルト。本物との突き合わせが一番楽。SHA-256 は後回し）
- **loose object 形式のみ**（`objects/ab/cdef...`。packfile は作らない＝相互運用の最小ラインはこれで満たせる）
- commit は **単一親から始める**（マージで複数親に拡張）
- 北極星は「**自作で作った `.git` を本物 git が読める**」

**チェックポイント**
- [ ] `lein repl` で REPL が起動し、空の名前空間がロードできる
- [ ] 実物の `.git` の中の blob / tree / commit を `git cat-file` で目視した
- [ ] 「blob→tree→commit→refs→index→merge」を自分の言葉で一言ずつ説明できる

---

## フェーズ1：プランビング（低レベル）Day 1–4

### Day 1 — オブジェクトストレージ（hash-object / cat-file）

**目標**: 中身を SHA-1 でアドレス化して zlib 圧縮保存し、hash から読み戻す。**Git の心臓部＝KVストア**を作る。

**やること**
- オブジェクト形式を実装：`<type> <size>\0<content>` を作り、**この全体（ヘッダ込み）の SHA-1** を計算 → それがオブジェクトの ID。
- zlib 圧縮して `.git/objects/<hash[0:2]>/<hash[2:]>` に書く（`write-object`）。
- hash を受け取り、ファイルを読んで zlib 展開し、ヘッダを剥がして中身を返す（`read-object` / `cat-file`）。
- `hash-object`（中身→保存して hash を返す）と `cat-file`（hash→中身）を実装。

**概念**
- **content-addressing**：ID が中身から決まるので、同じ中身は必ず同じ hash＝自動で重複排除される。「なぜ commit hash は中身が同じなら一致するのか」の正体。
- SHA-1 は**圧縮前**の `header + content` に対して計算する（圧縮後ではない）。ここは間違えやすい。

**Clojureヒント**
- SHA-1：`(java.security.MessageDigest/getInstance "SHA-1")` → `.digest` → 16進文字列化。
- zlib：`java.util.zip.DeflaterOutputStream` / `InflaterInputStream`。
- ヘッダの `\0` 区切りはバイト単位で扱う（`String` にしてから split しない。バイナリ安全に）。

**チェックポイント**
- [ ] `(hash-object "hello\n")` が本物の `git hash-object` と**同じ hash** を返す ← いきなり参照オラクルで検証できる
- [ ] 🎯 自作で保存した blob を、本物の **`git cat-file -p <hash>` が読める**
- [ ] 逆に、本物 git が作った blob を自作 `cat-file` が読める

---

### Day 2 — tree オブジェクト（ディレクトリの表現）

**目標**: ディレクトリを tree オブジェクトとして表現し、blob と別 tree を指す再帰構造を作る。

**やること**
- tree エントリ形式：`<mode> <name>\0<20バイトの生SHA-1>` を**名前順にソートして連結**し、それを1つの tree オブジェクトとして保存。
- mode は `100644`（通常ファイル）/ `100755`（実行可能）/ `40000`（ディレクトリ）。
- tree を読み出してエントリ一覧（mode, name, hash）に戻す（`read-tree` の読み側）。

**概念**
- tree が blob（ファイル）と別の tree（サブディレクトリ）を指すことで、**ディレクトリツリー全体が1つの hash に畳み込まれる**（Merkle tree）。ルート tree の hash が変われば、どこかのファイルが変わったということ。
- hash は**16進文字列ではなく 20 バイトの生バイト**で埋め込む（ここが最頻出の落とし穴）。

**Clojureヒント**
- エントリのバイト連結は `ByteArrayOutputStream` + `DataOutputStream`。生 SHA-1 は 16進文字列を 20 バイトに戻して `.write`。
- ソート順は git の規則に従う（基本は名前のバイト順）。まずは単純な名前順で本物と突き合わせて確認。

**チェックポイント**
- [ ] ネストしたディレクトリを tree で表現できる
- [ ] 自作で作った tree を本物 `git cat-file -p <tree-hash>` が正しく展開する
- [ ] ルート tree の hash が、本物 git の `git write-tree` 結果と一致する

---

### Day 3 — commit と refs / HEAD

**目標**: スナップショット（commit）を作り、ブランチ（ref）と HEAD で履歴の骨格を組む。

**やること**
- commit オブジェクト（テキスト形式）を実装：
  ```
  tree <tree-hash>
  parent <parent-hash>      ← 最初のコミットは無し。マージで複数行
  author <name> <email> <unix-timestamp> <tz>
  committer <name> <email> <unix-timestamp> <tz>

  <commit message>
  ```
- `commit-tree`（tree hash + 親 + メッセージ → commit オブジェクト保存）を実装。
- `refs/heads/<branch>` に commit hash を書く（`update-ref`）。`HEAD` は `ref: refs/heads/main` という**シンボリック参照**。

**概念**
- **ブランチは「commit hash を1行書いただけのファイル」**。これが分かると git の body が半分見える。`git branch` が一瞬なのは当然だと腹落ちする。
- HEAD → ref → commit → tree → blob という**参照の連鎖**。`detached HEAD` は HEAD が ref ではなく commit を直接指す状態、と説明できるようになる。

**Clojureヒント**
- author/committer 行のタイムスタンプは Unix 秒 + `+0900` のような TZ。本物と hash を合わせたいなら、この形式を厳密に。
- ref の読み書きは単なるテキストファイル I/O。symbolic ref（`ref: ...`）と直接 hash の両対応を最初から意識。

**チェックポイント**
- [ ] 🎯 自作で作った commit を本物 `git cat-file -p <commit-hash>` / `git log <hash>` が読める
- [ ] `refs/heads/main` に hash を書くと、本物 git がそのブランチを認識する
- [ ] 履歴の骨格（commit が親を指す）ができた

---

### Day 4 — index（ステージングエリア）

**目標**: 作業ディレクトリとリポジトリを橋渡しする `.git/index` を読み書きする。**「なぜ add と commit が分かれているか」を手で理解する。**

**やること**
- `.git/index` のバイナリ形式を実装：
  - 12バイトヘッダ：`DIRC`（マジック）+ バージョン(4) + エントリ数(4)
  - 各エントリ：ctime/mtime/dev/ino/mode/uid/gid/size（各種メタ）+ 20バイトSHA + フラグ + パス名 + 8バイト境界へのnullパディング
  - 末尾：ファイル全体の SHA-1 チェックサム
- index の読み込み・書き出しを実装（`ls-files` 相当で中身を一覧できると検証が楽）。

**概念**
- index は**「次の commit の下書き」**。作業ディレクトリの現在の姿でも、最後の commit の姿でもない、第3の状態。この三者があることが git のワークフロー（Day 9 の status）の土台。
- ここが**プロジェクトで一番ややこしいバイナリ形式**。逆に言えば、ここを越えると残りは楽。

**Clojureヒント**
- `ByteBuffer`（big-endian）で固定長フィールドを順に読む/書く。パディング計算（8バイト境界）を関数に切り出す。
- メタ情報（ctime 等）は本物と完全一致させる必要はまずない（git は再ステージで埋め直す）。まずは mode/size/sha/path が正しく往復することを優先。

**チェックポイント**
- [ ] 自作で書いた index を本物 `git ls-files --stage` が読める（or 本物の index を自作が読める）
- [ ] index の読み書きが往復する（書いて読んで一致）
- [ ] staging という中間状態が「なぜ要るか」を自分の言葉で説明できる

---

## フェーズ2：ポーセリン（高レベル）Day 5–9

### Day 5 — add / commit 一気通貫 ← **最初の大きな山**

**目標**: 低レベル部品を繋いで、`init → add → commit` が一気通貫で動くようにする。

**やること**
- `add <file>`：ファイルを blob として保存（Day 1）→ index にエントリ追加（Day 4）。
- `commit <message>`：index から tree を構築（Day 2 の `write-tree`）→ 現在の HEAD を親にして commit 作成（Day 3）→ HEAD の指すブランチ ref を新 commit に更新。
- `init`：`.git` の骨組み（`objects/` `refs/heads/` `HEAD`）を作る。

**概念**
- ポーセリン = プランビングの組み合わせ、という Git の設計思想を体感する。`git add` は「blob 保存 + index 更新」、`git commit` は「index→tree→commit→ref 更新」という**部品の連鎖**でしかない。

**Clojureヒント**
- ここは新しい低レベル実装はほぼ無く、Day 1–4 の関数を呼ぶ「配線」。関数境界が綺麗なら配線も綺麗になる。詰まったら下の層を REPL で単体確認。

**チェックポイント**
- [ ] 🎯 自作エンジンだけで `init → add → commit` したリポジトリを、**本物 `git log` / `git status` / `git show` が正しく読む**（最大の達成感ポイント）
- [ ] 複数ファイルを add → commit できる
- [ ] 2回目の commit が1回目を親に持つ

---

### Day 6 — log と diff

**目標**: 親ポインタを辿って履歴を表示し、2つの状態の差分を出す。

**やること**
- `log`：HEAD から parent を辿って commit を順に表示（hash / author / message）。
- `diff`：まずは「2つの tree の比較」→ 追加/削除/変更されたファイル一覧。中身の行差分は Myers 差分（簡易でOK、まずはファイル単位の add/del/mod から）。

**概念**
- 履歴走査は**片方向連結リスト（or DAG）の走査**。マージがあると commit が複数親を持つので DAG になる（Day 11 の伏線）。
- git は差分ではなく**スナップショットを保存**している。diff は保存時ではなく**表示時に計算**される。「git は差分を持たない」の腹落ち。

**Clojureヒント**
- log は `iterate`／lazy seq で親を辿ると綺麗。tree 比較は両 tree のエントリを名前でマージ（LSM の k-way マージの感覚が効く）。
- 行差分まで作るなら Myers 差分（後述リファレンス）。ただし初回はファイル単位の差分で十分。

**チェックポイント**
- [ ] 自作 `log` の出力が本物 `git log --oneline` と（hash も）一致する
- [ ] 2 commit 間で変わったファイルを列挙できる

---

### Day 7 — branch / tag

**目標**: ブランチ＝ポインタであることを手で確かめ、参照操作を実装する。

**やること**
- `branch <name>`：`refs/heads/<name>` に現在の commit hash を書くだけ。一覧表示も。
- `tag <name>`：まずは軽量タグ（`refs/tags/<name>` に hash を書く）。
- HEAD の付け替え（`switch` の下準備）。

**概念**
- 「ブランチ作成が一瞬な理由」＝**ファイルを1つ書くだけ**だから。ブランチは重い実体ではなくポインタ。ここが Day 3 で撒いた種の回収。

**Clojureヒント**
- refs 操作は薄いテキスト I/O。Day 3 の `update-ref` を再利用。ブランチ一覧は `refs/heads/` のディレクトリ走査。

**チェックポイント**
- [ ] `branch` で作ったブランチを本物 `git branch` が認識する
- [ ] ブランチが「hash を指すファイル」だと目視で確認した

---

### Day 8 — checkout / read-tree（tree → 作業ディレクトリの逆方向）

**目標**: これまで「作業dir → リポジトリ」だった向きを逆転させ、commit の中身を作業ディレクトリに展開する。

**やること**
- `read-tree`：tree を再帰的に読んで index に展開。
- `checkout <branch>`：対象ブランチの tree を作業ディレクトリに書き出し + index 更新 + HEAD 切り替え。
- まずは「クリーンな作業ディレクトリ前提」で単純に。未コミット変更の保護は後回しでよい。

**概念**
- checkout は**リポジトリの状態で作業ディレクトリを上書きする**操作。ここで初めて「双方向」が揃い、git の往復（保存と復元）が完成する。

**Clojureヒント**
- tree の再帰展開は Day 2 の読み側 + ディレクトリ作成。blob を書き出す際は mode（実行ビット）に注意（まずは無視でも可）。

**チェックポイント**
- [ ] ブランチを切り替えると作業ディレクトリの中身が変わる
- [ ] checkout 後の状態を本物 git が「clean」と認識する（相互運用が壊れていない）

---

### Day 9 — status / reset（三者比較で世界観が閉じる）

**目標**: 作業ディレクトリ・index・HEAD の**三者の差分**を出し、Git の世界観を完成させる。

**やること**
- `status`：3つを両々比較する。
  - HEAD tree ↔ index：**staged**（commit 予定の変更）
  - index ↔ 作業ディレクトリ：**not staged**（まだ add してない変更）
  - どちらにも無い：**untracked**
- `reset`（余力で）：HEAD / index を巻き戻す（`--soft` / `--mixed` の違いを実感）。

**概念**
- `git status` の「Changes to be committed」「Changes not staged」「Untracked」の3区分が、**そのまま三者比較の結果**だと分かる。ここで Day 4 の index が「なぜ要るか」が完全に回収される。

**Clojureヒント**
- 3つの状態をそれぞれ `{path → hash}` のマップにして、集合演算（key の差・値の差）で分類すると綺麗。ここも LSM のマージ感覚が効く。

**チェックポイント**
- [ ] 自作 `status` の分類が本物 `git status` と一致する
- [ ] index の役割を「三者の中間」として説明できる
- [ ] **フェーズ2完了**：日常コマンドが一通り自分の手で動く

---

## フェーズ3：マージ & 仕上げ（Day 10–12）

### Day 10 — 相互運用テスト（本物 git を参照オラクルに）

**目標**: これまで各日でやってきた突き合わせを、**体系的なテストスイート**に格上げする。

**やること**
- **双方向の相互運用テスト**を書く：
  - (A) 自作で `init→add→commit→branch→checkout` した repo に対し、`git fsck` / `git log` / `git status` をシェルから叩いて整合を確認。
  - (B) 本物 git で作った repo を自作エンジンが読めることを確認。
- property test（`test.check`）で、ランダムなファイル内容を hash-object → 本物 `git hash-object` と hash 一致、を大量ケースで検証（LSM / 正規表現でやった参照オラクル検査の再演）。

**概念**
- 「本物 git が読める＝バイトレベルで正しい」という、**この題材ならではの強力な検証**。自作の正しさを主観ではなくオラクルで担保する文化を、また一段強くする。

**Clojureヒント**
- シェル呼び出しは `clojure.java.shell/sh`。`git fsck` が通れば構造的にはほぼ正しい。
- `test.check` は「入力生成 → 自作と本物の両方に流す → 一致アサート」の形。

**チェックポイント**
- [ ] `git fsck` が自作 repo に対してエラーを出さない
- [ ] ランダム入力で自作 hash と本物 hash が常に一致する

---

### Day 11 — マージ①：共通祖先と 3-way merge の骨格

**目標**: 2つのブランチの**共通祖先（merge-base）**を見つけ、3-way merge の枠組みを作る。

**やること**
- `merge-base`：2つの commit から親を辿り、**最近共通祖先**を求める（commit DAG の探索）。
- ファイル単位の 3-way merge の骨格：base / ours / theirs の3つの tree を比較し、
  - 片方だけが変更 → その変更を採用
  - 両方が同じ変更 → 採用
  - 両方が別々に変更 → **コンフリクト**

**概念**
- **なぜ3つ必要か**：ours と theirs の2つだけでは「どちらが変えたのか」が分からない。base（共通祖先）と比べて初めて「誰が何を変えたか」が確定する。これが 3-way merge の核心。
- merge commit は**親を2つ持つ** commit。ここで commit が DAG になる（Day 6 の伏線回収）。

**Clojureヒント**
- merge-base は BFS で各 commit の祖先集合を作り、最初に交わる点を取る簡易版から。
- tree 3つの比較は、全 path を集めて `{path → [base ours theirs]}` にしてから分類すると見通しが良い。

**チェックポイント**
- [ ] 2ブランチの共通祖先が正しく求まる
- [ ] ファイル追加/片側変更が自動マージされる（コンフリクトにならない）

---

### Day 12 — マージ②：行レベルのコンフリクト表現・README・振り返り

**目標**: 同一ファイル内の変更を行レベルでマージし、コンフリクトを git 形式で表現。人に見せられる状態にして締める。

**やること**
- **行レベル 3-way merge（diff3）**：base に対する ours / theirs の行差分を取り、チャンクごとに「両者一致→採用 / 片側のみ→採用 / 両側衝突→コンフリクト」を判定。
- コンフリクトは git 形式のマーカーで出力：
  ```
  <<<<<<< ours
  （ours の行）
  =======
  （theirs の行）
  >>>>>>> theirs
  ```
- マージ成功時は親2つの merge commit を作成。
- **README を書く**：アーキ図、オブジェクト形式（blob/tree/commit/index のバイト構造）、参照オラクル戦略、設計判断とトレードオフ、相互運用の実演コマンド。
- **振り返り**：Pro Git 第10章を**もう一度読む**。初日と違って全部「あ、これ作った」になっているはず。

**概念**
- コンフリクトが起きるのは「base から見て両側が同じ箇所を別々に変えた」時だけ、と手で分かる。**「なぜコンフリクトが起きるのか／なぜ起きない変更もあるのか」の完全な回収。**

**Clojureヒント**
- diff3 は「共通部分でチャンク分割 → 各チャンクを分類」。まずは行単位・シンプルな LCS ベースで十分。Coglan の diff3 記事が実装の道しるべ（下記リファレンス）。

**チェックポイント**
- [ ] 同一ファイルの非衝突な変更が行レベルで自動マージされる
- [ ] 衝突するとコンフリクトマーカーが出て、本物 git 形式と互換
- [ ] merge commit が親を2つ持ち、本物 `git log --graph` が正しく描く
- [ ] README だけ読めば設計が分かる
- [ ] **完成**

---

## 完成の定義（Definition of Done）

- [ ] `init / add / commit` が動き、**本物 git がそのリポジトリを読める**
- [ ] blob / tree / commit を保存・読み出しでき、hash が本物と一致する
- [ ] refs / HEAD でブランチを表現し、`branch` / `checkout` が動く
- [ ] index を読み書きでき、`status` が三者比較で正しく分類する
- [ ] `log` で履歴を辿り、`diff` で差分を出せる
- [ ] `merge-base` で共通祖先を求め、3-way merge ができる
- [ ] コンフリクトが git 形式のマーカーで表現される
- [ ] merge commit が複数親を持つ
- [ ] 本物 git を参照オラクルにした相互運用テストがある
- [ ] README がある

---

## リファレンス

- **Pro Git 第10章 "Git Internals"** — このカリキュラムの背骨。最重要。無料。
  https://git-scm.com/book/en/v2/Git-Internals-Plumbing-and-Porcelain
- **Write Yourself a Git (WYAG)** — Python でミニ git を作る無料チュートリアル。実装の伴走に。
  https://wyag.thb.lt/
- **"Building Git" — James Coglan** — Ruby で git を一から作る書籍（有料）。マージ/差分まで最も詳しい。
  https://shop.jcoglan.com/building-git/
- **Merging with diff3 — James Coglan** — Day 11–12 の 3-way merge の実装指針。
  https://blog.jcoglan.com/2017/05/08/merging-with-diff3/
- **The Myers diff algorithm (part 1) — James Coglan** — Day 6 / 12 の行差分を作るなら。
  https://blog.jcoglan.com/2017/02/12/the-myers-diff-algorithm-part-1/
- Clojure/JVM: `java.security.MessageDigest`（SHA-1）, `java.util.zip.Deflater`/`Inflater`（zlib）, `java.nio.ByteBuffer`, `clojure.java.shell`（本物 git 呼び出し）, `org.clojure/test.check`。

---

## ストレッチ目標（12日で終わって余ったら）

- **packfile の読み取り**：複数オブジェクトを1ファイルに固めた形式 + デルタ圧縮を「読むだけ」実装。実務で `.git` が肥大化する理由が分かる。書き込みは重いので読み取りだけで十分学べる。
- **rev-parse / 短縮 hash 解決**：`HEAD~2` や `main^` のような参照式のパース。
- **`.gitignore` 対応**：untracked 判定にパターンマッチを足す。
- **SHA-256 リポジトリ**：ハッシュ方式を差し替え可能に設計してあれば、対応は局所的。
- **次のプロジェクトへの接続**：ここで作った content-addressable なオブジェクトストアの発想は、次に予定している **Redis（AOF＝また WAL）/ Kafka（append-only ログ）** の永続化層と地続き。KV → ログ構造という一本の線で繋がる。

---

## 進め方のコツ

- **毎日「本物 git で答え合わせ」する**。このカリキュラム最大の武器。`git cat-file` / `git fsck` / `git log` がその日の実装を即座に検証してくれる。Day 1（hash 一致）、Day 5（本物が読む repo が完成）、Day 11–12（マージが動く）が気持ちいいポイント。
- **低レベル（Day 1–4）を焦らない**。ここが土台。特に index（Day 4）のバイナリ形式は丁寧に。ここさえ越えれば後半は配線が中心で軽い。
- **REPL 駆動で確認しながら**。一気に書かず、`hash-object` 単体、`read-tree` 単体、と関数単位で本物と突き合わせる。
- **完璧を目指さない**。「教科書的に正しい」より「自分が仕組みを説明できる」を優先。mode の細かい違いや ctime の完全一致は後回しでよい（`git fsck` が通ることを先に）。
- **GitHub に incremental に push**。コンテキストが切れたら、scratch ではなく push 済みの状態を正とする（LSM / 検索エンジンと同じ運用）。