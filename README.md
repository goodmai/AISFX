# AI Cross-Platform Application

## Features
- Vertical navigation panel with main functions
- Split-screen UI for request/response
- State persistence
- History tracking
- Multi-model AI support
- File context handling with confirmation dialogs
- Smart model switching with fallback mechanisms

## Architecture
- **MVC Pattern**: Clear separation between components
- **Pekko Actors**: For async history management
- **ScalaFX**: Reactive UI components
- **JSON Serialization**: Using uPickle for state persistence
- **Request Pipeline**: Modular request processing with context handling
- **File Manager**: Intelligent file context processing and validation

## Best Practices
1. Actor-based concurrency for history management
2. Immutable state handling
3. Dependency separation
4. Type-safe configuration
5. Proper error handling
6. Content validation and sanitization
7. User confirmation for sensitive operations
8. Graceful fallback mechanisms

## Build & Run
```sbt
sbt compile
sbt run