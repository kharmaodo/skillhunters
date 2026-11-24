"""Offline scoring of exhaustively judged rankings; never calls the application."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identifiers(values, label):
    require(isinstance(values, list) and values, f'{label}: non-empty list required')
    require(all(isinstance(v, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,100}', v) for v in values),
            f'{label}: opaque identifiers required')
    require(len(set(values)) == len(values), f'{label}: duplicate identifier')
    return set(values)


def evaluate(judgments, results):
    """Grades 0..3; macro averages exclude queries with no relevant documents."""
    require(judgments.get('schemaVersion') == 1 and results.get('schemaVersion') == 1,
            'schemaVersion must be 1')
    require(judgments.get('split') == 'validation', 'validation split required')
    documents = judgments.get('documents')
    require(isinstance(documents, list), 'documents required')
    universe = identifiers([d['id'] for d in documents], 'documents')
    formats = {d['id']: d['format'] for d in documents}
    require(all(v in {'DOCX_TEMPLATE', 'DOCX_FREE', 'DOC', 'PDF_TEXT', 'PDF_SCAN', 'PDF_MIXED', 'MD'}
                for v in formats.values()), 'unknown document format')
    queries = judgments.get('queries')
    require(isinstance(queries, list), 'queries required')
    query_ids = identifiers([q['id'] for q in queries], 'queries')
    rankings = results.get('rankings')
    require(isinstance(rankings, dict) and set(rankings) == query_ids,
            'rankings must cover exactly all judged queries')
    require(isinstance(results.get('commit'), str) and re.fullmatch(r'[0-9a-f]{40}', results['commit']),
            'full application commit required')
    require(isinstance(results.get('configurationSha256'), str)
            and re.fullmatch(r'[0-9a-f]{64}', results['configurationSha256']), 'configuration SHA-256 required')
    rows = []
    by_format = {f: [] for f in sorted(set(formats.values()))}
    for query in queries:
        qid = query['id']
        grades = query.get('grades')
        require(isinstance(grades, dict) and set(grades) == universe,
                'each query must explicitly judge every document, including grade zero')
        require(all(type(g) is int and 0 <= g <= 3 for g in grades.values()), 'grades must be integers 0..3')
        ranked = rankings[qid]
        require(isinstance(ranked, list), 'ranking must be an ordered list')
        require(all(isinstance(d, str) and d in universe for d in ranked), 'ranking contains unjudged document')
        require(len(ranked) == len(set(ranked)), 'ranking contains duplicate document')
        relevant = {d for d, g in grades.items() if g > 0}
        top20 = set(ranked[:20])
        recall = len(top20 & relevant) / len(relevant) if relevant else None
        dcg = sum((2 ** grades[d] - 1) / math.log2(i + 2) for i, d in enumerate(ranked[:10]))
        ideal = sum((2 ** g - 1) / math.log2(i + 2)
                    for i, g in enumerate(sorted(grades.values(), reverse=True)[:10]))
        ndcg = dcg / ideal if ideal else None
        rows.append({'queryId': qid, 'relevant': len(relevant), 'returned': len(ranked),
                     'recallAt20': recall, 'ndcgAt10': ndcg,
                     'unexpectedResultsForEmptyQuery': len(ranked) if not relevant else 0})
        for fmt in by_format:
            applicable = {d for d in relevant if formats[d] == fmt}
            if applicable:
                by_format[fmt].append(len(top20 & applicable) / len(applicable))
    def mean(values):
        return sum(values) / len(values) if values else None
    recall = mean([r['recallAt20'] for r in rows if r['recallAt20'] is not None])
    ndcg = mean([r['ndcgAt10'] for r in rows if r['ndcgAt10'] is not None])
    empty_errors = sum(r['unexpectedResultsForEmptyQuery'] for r in rows)
    sufficient = len(documents) >= 100 and len(queries) >= 30 and recall is not None
    meets = sufficient and recall >= .9 and ndcg >= .8 and empty_errors == 0
    return {'schemaVersion': 1, 'commit': results['commit'],
            'configurationSha256': results['configurationSha256'],
            'documents': len(documents), 'queries': len(queries),
            'scorableQueries': sum(r['relevant'] > 0 for r in rows),
            'macroRecallAt20': recall, 'macroNdcgAt10': ndcg,
            'unexpectedResultsForEmptyQueries': empty_errors,
            'recallAt20ByFormat': {f: {'scorableQueries': len(v), 'macroRecallAt20': mean(v)}
                                   for f, v in by_format.items()},
            'proposedTargets': {'minimumDocuments': 100, 'minimumQueries': 30,
                                'recallAt20': .9, 'ndcgAt10': .8},
            'technicalDecision': 'MEETS_PROPOSED_TARGETS' if meets else 'DOES_NOT_MEET_PROPOSED_TARGETS',
            'productionDecision': 'NOT_APPROVED_BY_THIS_REPORT', 'perQuery': rows}


def load(path):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'duplicate JSON key')
            value[key] = item
        return value
    raw = path.read_bytes()
    return json.loads(raw, object_pairs_hook=unique), hashlib.sha256(raw).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('judgments', type=Path)
    parser.add_argument('results', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    try:
        require(args.report.resolve() not in {args.judgments.resolve(), args.results.resolve()},
                'report must not overwrite an input')
        judgments, digest = load(args.judgments)
        results, results_digest = load(args.results)
        require(results.get('judgmentsSha256') == digest, 'results reference another judgments file')
        report = evaluate(judgments, results)
        report.update(judgmentsSha256=digest, resultsSha256=results_digest)
        with args.report.open('x') as output:
            output.write(json.dumps(report, indent=2, allow_nan=False) + '\n')
        print(report['technicalDecision'] + '; production remains unapproved')
        return 0 if report['technicalDecision'] == 'MEETS_PROPOSED_TARGETS' else 1
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        # Never echo input contents, paths or identifiers from potentially private data.
        print('Invalid qualification inputs; no new report written.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
