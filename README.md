JARVIS AI for Android

JARVIS is an Android-native AI assistant built with Kotlin and Jetpack Compose.

The goal is to create a useful personal AI assistant for everyday conversations, tasks and automation, with an emphasis on reliability, privacy, security and user control.

Current development status

JARVIS is actively under development. The project is currently working through its provider architecture and AI integrations.

Implemented capabilities

- Multiple AI providers: Gemini, Groq, OpenRouter, Cloudflare and Z.ai.
- Provider configuration: Configure API keys, select models and test connections.
- Chat provider selection: Independently choose which configured provider handles conversations.
- Provider failover: Switch providers when certain failures occur.
- Provider status management: Track configuration, connection tests and active chat-provider status.
- Security improvements: API credential handling and HTTP redirect protection.
- Reliability: Automated tests, CI builds and ongoing architectural improvements.

Features and implementation details are still evolving.

Development roadmap

Planned improvements include:

- Intelligent long-term memory.
- Better context understanding and response presentation.
- A beginner-friendly interface and onboarding experience.
- Image, document, video and camera support.
- More useful everyday automation.
- Further security, privacy and reliability improvements.

These are planned features and are not all currently available.

AI providers and costs

JARVIS supports multiple AI providers. Users may need to configure their own API keys.

Provider pricing, free-tier availability, rate limits and usage restrictions depend on the respective provider and may change. Always check the provider's terms before using its services.

JARVIS does not guarantee that every provider or model is free.

Getting involved

JARVIS is an open development project, and constructive feedback is welcome.

You can help by:

- Reporting bugs and unexpected behaviour.
- Suggesting features and improvements.
- Reviewing architecture and code.
- Identifying security or reliability concerns.
- Proposing code contributions.

All contributions should be reviewed and tested before integration.

Signing and installation warning

The old signing key that previously existed in this repository is public and must not be trusted.

Builds from Build 41 onward use a replacement signing key stored outside the repository. Moving from an older installation may require uninstalling the old app first. Uninstalling JARVIS deletes its locally stored chats, memories and saved API keys.

See "docs/SIGNING.md" (docs/SIGNING.md) for details.

---

Project status: In active development.

This repository is not a declaration of a finished or stable public release. Features, architecture and installation procedures may change as development progresses.