# AI Cross-Platform Application

## Features
- Vertical navigation panel with main functions
- Split-screen UI for request/response
- State persistence
- History tracking
- Multi-model AI support

## Architecture
- **MVC Pattern**: Clear separation between components
- **Pekko Actors**: For async history management
- **ScalaFX**: Reactive UI components
- **JSON Serialization**: Using uPickle for state persistence

## Best Practices
1. Actor-based concurrency for history management
2. Immutable state handling
3. Dependency separation
4. Type-safe configuration
5. Proper error handling

## Build & Run
```sbt
sbt compile
sbt run