# Evaluation

The evaluation package measures the public `TypedLLM` behavior rather than
calling validators/providers directly.

Flow:

    EvaluationCase
        -> EvaluationHarness
        -> TypedLLM.generate()
        -> CaseResult
        -> EvaluationSummary / reporting

Current measurements include:
- final structured-output success
- first-attempt success
- attempts and retries
- expected-output match when an expectation is supplied
- latency
- input/output token counts when the provider reports them
- provider/model
- error text

Future additions can include fabrication rate, cost, concurrency/scaling, and
provider/configuration comparisons.
