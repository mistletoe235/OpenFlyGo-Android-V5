# Contributing

Open an issue before a large behavioral or flight-control change. Keep pull requests focused, add
tests for safety policies and coordinate transforms, and run both `./gradlew testFullDebugUnitTest`
and `./gradlew testRc2LiteDebugUnitTest` before submission.

Never commit DJI or map keys, signing material, access tokens, aircraft identifiers, account data,
flight logs, or media captured from a real flight. Real-aircraft changes must include simulator
evidence and a written safety review; simulator success alone is not flight acceptance.
