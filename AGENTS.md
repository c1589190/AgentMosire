# Repository Guidelines

## Project Structure

The workspace starts with this guide. As the first components land, document where contributors can find source code, tests, and assets in `README.md`. Follow the chosen language or framework’s directory conventions. Keep test fixtures near the tests that use them.

## Build, Test, and Development Commands

When introducing a toolchain, add the required runtime version, dependency installation steps, and commands to build, run, and test the project to `README.md`. Specify the working directory for commands that run outside the repository root. Run the relevant checks locally before requesting review.

## Coding Style and Naming

Use the indentation and naming conventions of the project’s language and follow nearby code when adding files and symbols. If you introduce a formatter or linter, include its configuration and document the command contributors can use to check their changes.

## Testing

Follow the test runner’s conventions for naming and placing tests once the runner is in use. For a bug fix, add a regression test that reproduces the failure when practical. When introducing the test suite, document how to run the full suite and an individual test.

## Commits and Pull Requests

Use short, imperative commit subjects such as `Add project setup instructions`. Keep each pull request focused on one change. Explain what changed and why, list the checks you ran and their results, and link any related issue. Include screenshots for visible changes when they help reviewers verify the result.
