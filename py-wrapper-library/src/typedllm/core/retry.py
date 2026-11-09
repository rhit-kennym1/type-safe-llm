"""Retry and repair behavior.

Responsibilities:
- decide whether retry is appropriate
- construct retry context
- preserve already-correct values when possible
- avoid pushing the model to invent missing values
- stop after configured limits
"""
