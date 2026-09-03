"""Grade the public-benchmark reports the phones wrote, with each benchmark's own rules.

    python3 tools/eval/bench/grade.py tools/eval/results

GSM8K: the last number in the reply equals the reference (the dataset's own "####" rule,
read leniently because a phone-sized model writes "#### <number> 9" or "\\boxed{9}").
IFEval: the instruction checkers from google-research/instruction_following_eval, vendored
under ifeval/ (Apache-2.0), strict accuracy: every instruction in the prompt satisfied.
BFCL: the leaderboard's AST rule for the simple and multiple categories, reimplemented:
exactly one call, to the right function, every required parameter present, every given
value among the possible answers (a "" among them means the parameter may be omitted).
Only the parsed calls count, the way the app would act on them; text is not re-parsed.

Writes a *.graded.json beside each report and prints a summary; report.py renders it.
Needs the packages in requirements.txt and nltk's punkt_tab data (see that file).
"""
import json, random, re, sys

import langdetect
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ifeval import instructions_registry  # noqa: E402

PROMPTS = {p["id"]: p for p in json.loads(
    (Path(__file__).resolve().parent / "benchmarks.json").read_text())["prompts"]}

NUMBER = re.compile(r"-?\d[\d,]*(?:\.\d+)?")
BOXED = re.compile(r"\\boxed\{\s*(-?\d[\d,]*(?:\.\d+)?)\s*\}")


def strip_thinking(text: str) -> str:
    if "</think>" in text:
        return text.split("</think>")[-1]
    # A thinking block the cap cut open never reached an answer.
    return "" if "<think>" in text else text


def grade_gsm8k(case, ref) -> bool:
    """The dataset's own '####' marker first, then a boxed answer, then the last number.

    A phone-sized model writes '#### <number> 9', '\\boxed{9}' or just '9' at the end;
    the marker wins when present so a trailing remark cannot supply the number."""
    text = strip_thinking(case.get("content") or "")
    if "####" in text:
        nums = NUMBER.findall(text.split("####")[-1])
        if nums:
            return same_number(nums[0], ref)  # the first number after the marker is the answer
    boxed = BOXED.findall(text)
    if boxed:
        return same_number(boxed[-1], ref)
    nums = NUMBER.findall(text)
    return bool(nums) and same_number(nums[-1], ref)


def same_number(text: str, ref) -> bool:
    try:
        return float(text.replace(",", "")) == float(ref)
    except ValueError:
        return False


def grade_ifeval(case, ref) -> bool:
    """The strict rule of google-research's evaluation_lib.test_instruction_following_strict,
    instruction by instruction: build with the dataset's kwargs, then with the prompt for
    the checkers that read it, then check. langdetect is seeded, and so is random, because
    both are consulted by some checkers and an unseeded grade is not a grade."""
    response = strip_thinking(case.get("content") or "")
    prompt = PROMPTS[case["id"]]["prompt"]
    if len(ref["instruction_id_list"]) != len(ref["kwargs"]) or not ref["instruction_id_list"]:
        raise ValueError(f"malformed IFEval reference for {case['id']}")
    langdetect.DetectorFactory.seed = 0
    random.seed(0)
    for iid, kwargs in zip(ref["instruction_id_list"], ref["kwargs"]):
        inst = instructions_registry.INSTRUCTION_DICT[iid](iid)
        inst.build_description(**kwargs)
        if "prompt" in inst.get_instruction_args_keys():
            inst.build_description(prompt=prompt)
        if not response.strip() or not inst.check_following(response):
            return False
    return True


def same(value, accepted) -> bool:
    for a in accepted:
        if value == a:
            return True
        if isinstance(a, (int, float)) and not isinstance(a, bool) and not isinstance(value, bool):
            try:
                if float(value) == float(a):
                    return True
            except (TypeError, ValueError):
                pass
        if isinstance(a, str) and isinstance(value, str) and value.strip().lower() == a.strip().lower():
            return True
        if isinstance(a, list) and isinstance(value, list) and len(a) == len(value) \
                and all(same(v, [x]) for v, x in zip(value, a)):
            return True
    return False


def grade_bfcl(case, ref) -> bool:
    calls = case.get("calls") or []
    if len(calls) != 1 or len(ref) != 1:
        return False
    (name, params), = ref[0].items()
    call = calls[0]
    if call["name"] != name and call["name"] != name.replace(".", "_"):
        return False
    try:
        args = json.loads(call["arguments"] or "{}")
    except ValueError:
        return False
    if not isinstance(args, dict):
        return False
    for p, accepted in params.items():
        if p not in args:
            if "" in accepted:
                continue
            return False
        if not same(args[p], [a for a in accepted if a != ""]):
            return False
    return all(k in params for k in args)


GRADERS = {"gsm8k": grade_gsm8k, "ifeval": grade_ifeval, "bfcl": grade_bfcl}


def grade_report(path: Path) -> dict:
    report = json.loads(path.read_text())
    if report.get("suite") != "benchmark":
        return {}
    tally = {}
    for case in report["cases"]:
        s = case["set"]
        t = tally.setdefault(s, {"pass": 0, "fail": 0, "skipped": 0, "error": 0})
        if case["status"] in ("skipped", "error"):
            t[case["status"]] += 1
            case["grade"] = case["status"]
            continue
        ok = GRADERS[s](case, PROMPTS[case["id"]]["reference"])
        case["grade"] = "pass" if ok else "fail"
        t["pass" if ok else "fail"] += 1
    report["tally"] = tally
    out = path.with_name(path.name.replace(".json", ".graded.json"))
    out.write_text(json.dumps(report, indent=1, ensure_ascii=False))
    return tally


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "tools/eval/results")
    for path in sorted(root.glob("*.bench*.json")):
        if ".graded." in path.name:
            continue
        tally = grade_report(path)
        if tally:
            print(path.name, {s: f"{t['pass']}/{t['pass'] + t['fail']}" for s, t in tally.items()})


if __name__ == "__main__":
    main()
