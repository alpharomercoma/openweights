# IFEval checker, vendored

`instructions.py`, `instructions_registry.py` and `instructions_util.py` are from
google-research/google-research, directory `instruction_following_eval`, fetched from the
`master` branch on 2026-09-03 (Apache-2.0, notices kept in the files). Two edits: package
imports made relative, and `count_sentences` uses `nltk.tokenize.sent_tokenize` because
nltk 3.9+ no longer loads the pickled tokenizer the original reached for.

`grade.py` reimplements the loop of the upstream `evaluation_lib.test_instruction_following_strict`
(build with the row's kwargs, rebuild with the prompt for checkers that take it, check each
instruction, all must pass) rather than importing the upstream module, which pulls absl and
the upstream data loader. It seeds `langdetect` and `random`, which the upstream harness does
not, so a grade here is reproducible.
