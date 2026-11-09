"""Main orchestration pipeline.

Expected flow:

    validate request/schema
        ->
    build prompt/messages
        ->
    call provider
        ->
    parse raw output
        ->
    validate parsed output
        ->
    success OR retry
        ->
    final result

This should coordinate components rather than implement all logic itself.
"""
