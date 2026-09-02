# APK build provenance

CI exports the current GitHub commit SHA and workflow run number into `BuildConfig.BUILD_COMMIT` and `BuildConfig.BUILD_RUN`.

The plugin manager displays `BuildProvenance.label`, so a phone screenshot can be matched to the exact GitHub build.

Both PR debug APKs and main release APKs are verified in CI by checking that the produced APK contains the expected commit SHA and run number before the artifact is uploaded.
