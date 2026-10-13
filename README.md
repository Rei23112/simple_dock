# Simple Dock

Android 16風のフローティングアプリドックです。Flutterで設定画面とアプリドロワーを、AndroidネイティブのForeground Serviceで他アプリ上のオーバーレイを実装しています。

## 実行

```bash
flutter pub get
flutter run
```

初回起動後、設定画面上部のスイッチをオンにし、「他のアプリの上に表示」を許可してください。許可後にスイッチを再度オンにするとドックが表示されます。

## 操作

- アプリドロワーのアプリを長押し: pin
- ドック上のアプリを長押し: pin / remove
- ドック上のアプリをタップ: 起動。recentに追加
- ドックを外側へスワイプ: 折りたたみ
- 画面端の矢印: ドックを復元

ホームアプリの起動はrecentに追加しません。キーボード表示中はAndroidのオーバーレイ制御により、入力を妨げない構成になっています。

## ビルド確認

```bash
flutter analyze
flutter test
flutter build apk --debug
```

オーバーレイ権限、アプリ一覧取得、Foreground ServiceはAndroid実機で確認してください。`QUERY_ALL_PACKAGES` は配布先ストアの審査ポリシーに従って用途を確認してください。
