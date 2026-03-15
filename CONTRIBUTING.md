# Contributing to Red Arrow

Thanks for your interest! Here's how you can help.

## Reporting Bugs

Open an [issue](https://github.com/JoursBleu/red-arrow/issues/new?template=bug_report.yml) with:

- Device model & Android version
- Steps to reproduce
- Expected vs actual behavior
- Logs (copy from the Log card in the app)

## Suggesting Features

Open an [issue](https://github.com/JoursBleu/red-arrow/issues/new?template=feature_request.yml) describing the use case and proposed solution.

## Pull Requests

1. Fork the repo and create a branch from `main`.
2. Make your changes with clear, focused commits.
3. Test on a real device or emulator (minSdk 26).
4. Run `./gradlew assembleDebug` to verify the build.
5. Open a PR against `main`.

### Code Style

- Kotlin with standard Android conventions
- 4-space indentation, no tabs
- Meaningful commit messages (`feat:`, `fix:`, `docs:`, `refactor:`)

### What we look for

- Does it compile?
- Is the change well-scoped (one concern per PR)?
- Are strings externalized to `strings.xml` for i18n?

## License

By contributing, you agree that your contributions will be licensed under CC BY-NC-SA 4.0.
