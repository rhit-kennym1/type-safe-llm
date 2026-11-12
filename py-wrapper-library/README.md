# Type-Safe LLM

Main flow:

    Application / UI / API
            |
            v
        TypedLLM
            |
            v
      Core Pipeline
    /      |       \
    Prompt   Provider   Validation
          |
          v
      Raw LLM Output
          |
          v
       Parse -> Validate
          |
    Valid? / \ Invalid?
          |     |
        Result Retry
                |
                +----> Provider again
