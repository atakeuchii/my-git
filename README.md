# my-git

Clojure で Git の内部実装をゼロから作り、「Git が実際にどうデータを保存・取得・統合しているか」を**手で理解する**ための学習プロジェクト。

本物の `git` を参照オラクル（正解）として、自作の出力が git に読めること・hash が一致することを常に検証しながら実装している。

---

## ゴールと方針

- Git を「使う側」ではなく「作る側」から理解する
- **実バイトを計測してから実装**する（`git cat-file` / hexdump で確認 → 実装 → 照合）
- 本物 git を oracle に `clojure.test` で固定する
- **一方向のレイヤ構成**（各層は自分より下位の層だけに依存する）

---

## アーキテクチャ（一方向レイヤ）

```
object (blob)          SHA-1 + zlib の content-addressed オブジェクトストア
  └─ tree              名前 → hash の目録（Merkle 構造）
       └─ commit       ある時点の tree スナップショット + 親 + 著者
            └─ ref/HEAD  ブランチ = commit を指す1行のファイル / HEAD = symbolic ref
index                  作業ツリーとリポジトリの中間（ステージングエリア, .git/index）
merge                  merge-base / 3-way tree merge
repo (porcelain)       init / add / commit / branch / checkout / status / merge
log                    commit-seq / log / diff
scenario, test-util    テスト・REPL 用の組み立て部品
```

- **object → tree → commit → ref** が「読み書きの背骨」
- **index** が add と commit を分ける緩衝地帯
- **repo** が下位を配線して日常コマンドにする最上層

---

## オブジェクト形式（実測済み）

すべて `<type> <size>\0<内容>` の SHA-1 を名前に、zlib 圧縮して `.git/objects/xx/yyyy…` に保存する（`xx` は hash 先頭2文字）。同じ内容なら必ず同じ hash＝同じ場所（重複排除・冪等）。

### blob（ファイルの中身）

```
blob <size>\0<ファイルの中身そのまま>
```

ファイル名は持たない（名前は tree 側の責務）。

### tree（ディレクトリの目録）

ソート済みエントリを区切りなしで連結：

```
<mode> <name>\0<20バイトの生SHA> <mode> <name>\0<20バイトの生SHA> ...
```

- mode: `100644`(通常ファイル) / `100755`(実行可) / `40000`(ディレクトリ)
- SHA は **40文字の16進ではなく 20バイトの生バイト**（最頻出の落とし穴）
- 並びは名前順。**ディレクトリは末尾に `/` を付けて比較**する
- tree は blob と別 tree の両方を指せる → ディレクトリツリー全体が1つの root hash に畳まれる（Merkle）

### commit（スナップショット）

```
tree <hash>
parent <hash>          ← 0個以上（最初は0、通常1、マージで2以上）
author <name> <email> <unix秒> <tz>
committer <name> <email> <unix秒> <tz>
                       ← 空行1つ
<コミットメッセージ>
```

hash は tree・親・著者・**日時**・メッセージのすべてに依存する（＝同じ内容でも日時や親が違えば別 hash）。

### index（ステージング, v2）

```
"DIRC" + version(4, =2) + エントリ数(4)          ← ヘッダ（ビッグエンディアン）
[エントリ] × N
  stat 40B (ctime/mtime/dev/ino/mode/uid/gid/size 各4B ×10)
  + 20B 生SHA + 2B flags(下位12bit=パス長) + パス + NULパディング(全体を8の倍数に)
20B の SHA-1                                     ← 先頭からここまで全体のチェックサム
```

index は tree と違い**フラットなフルパス一覧**。本実装では stat を mode 以外ゼロにしている（stat は変更検知の高速キャッシュに過ぎず、git は作業ツリーと再照合するため）。

---

## 参照の鎖

```
HEAD → refs/heads/<branch> → commit → tree → blob
```

- ブランチ = `refs/heads/<name>` に commit hash を1行書いたファイル
- HEAD = 通常は `ref: refs/heads/<branch>` の symbolic ref（commit を直接指すと detached）
- `commit` すると動くのは **HEAD ではなく、HEAD が指すブランチ**（HEAD はそれを介して自動追従）
- 履歴（親子関係）は **commit オブジェクトの `parent` 行**に記録される（ref は先頭を指すだけ）

---

## 実装済みコマンド

| コマンド | 内容 |
|---|---|
| `init` | 最小の `.git`（objects / refs/heads / HEAD symbolic ref / config）を作る |
| `add` | ファイルを blob 化して objects へ + index に upsert（削除もステージ） |
| `commit` | index → 階層 tree 構築 → commit（親=現HEAD）→ ブランチを前進 |
| `log` | parent 鎖を辿る遅延シーケンスを表示 |
| `diff` | 2つの tree を `{path→hash}` にして集合演算（追加 / 削除 / 変更） |
| `branch` / `checkout` / `switch` | ref 操作 + 作業ツリーへの materialize |
| `status` | HEAD tree / index / 作業ツリーの三者比較（staged / not staged / untracked） |
| `rm --cached` / `restore --staged` | unstage（index から落とす / HEAD の版へ戻す） |
| `merge` | merge-base + 3-way tree merge（fast-forward / merge commit〔親2つ〕/ 衝突検出） |

---

## 主要な設計判断とトレードオフ

- **既存オブジェクトはスキップ**：content-addressed なので同一 hash は再書き込みしない（git と同じ挙動。読み取り専用 loose object を上書きしようとする不具合も回避）
- **index の stat はゼロ**：変更検知の高速化のためのキャッシュに過ぎず、ゼロでも git は作業ツリーと再照合して正しく動く
- **checkout の materialize**：目標 tree に無い追跡ファイルを消し、各 blob を書き出し、index を目標 tree に一致させる（未追跡ファイルは触らない）
- **merge はファイル単位の 3-way**：base を基準に「片方だけ変更 → 採用 / 両方同じ変更 → 採用 / 両方別々に変更 → 衝突」を判定。行単位の統合は今後の課題（下記）

---

## テスト方針

- 本物 git を oracle にした `clojure.test`（`git cat-file` / `write-tree` / `ls-files` / `log` / `merge` などと照合）
- 著者日時を固定して commit hash を決定的に再現
- `scenario` の組み立て部品（`fresh-repo` → `write!` → `commit!` → `branch!` → `switch!` を `->` で連結）で状況を構築
- 移植性の注意：`git merge-tree --write-tree` は git 2.38+ 限定。古い環境でも動くよう、merge の照合は実 `git merge` → `rev-parse HEAD^{tree}` を oracle にする

---

## 今後のポイント（Git の理解がさらに深まる部分）

理解を深める観点で価値が高い順に絞って挙げる。

1. **行単位の 3-way マージ**
   現状はファイル単位（同じファイルを両方が触ったら即衝突）。diff3 で行レベルに統合し、重なった行だけ `<<<<<<<` / `=======` / `>>>>>>>` マーカーを書く。「なぜ衝突は行単位で起きるのか」「マーカーが何を意味するか」が腹落ちする。`merge-trees` の「衝突」を「行レベルで解決 or マーカー出力」に差し替えるだけなので、3-way merge の自然な締め。

2. **log のトポロジカル対応**
   現状の `commit-seq` は第1親だけを辿るため、merge commit があると片方の枝を見落とす。全親をトポロジカル順で辿る `git log` 相当に拡張すると、履歴が DAG（有向非巡回グラフ）であることと、その走査の理解が深まる。

3. **packfile の読み込み**
   本物の git は loose object だけでなく `.pack`（delta 圧縮された多数オブジェクトの束）にまとめる。ここを読めるようにすると「本物のリポジトリを丸ごと読む」に近づき、Git の実運用フォーマットとオブジェクト圧縮の実際が分かる。単独で重量級。

4. **`show` / `reset` / `tag`**
   既存部品の組み合わせで書ける小物。`show` は commit→tree→blob を降りる、`reset` は HEAD/index/作業ツリーのどこまで戻すか（`--soft`/`--mixed`/`--hard`）、`tag` は ref の一種。参照の鎖と index/HEAD 操作の理解を固めるのに良い。

---

## 参考

- Pro Git, Chapter 10 (Git Internals)
- "Building Git" — James Coglan