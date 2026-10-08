#!/usr/bin/env python3
"""Offline consistency checks for L0 artifacts; not an API implementation test."""
import json
import re
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def walk(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def check_contract():
    spec = json.loads((ROOT / 'docs/api/openapi.json').read_text())
    require(spec['openapi'] == '3.1.0', 'Unexpected OpenAPI version')
    require(bool(spec['security']), 'Global authentication is required')
    for value in walk(spec):
        if not isinstance(value, dict):
            continue
        if '$ref' in value:
            ref = value['$ref']
            require(ref.startswith('#/'), f'Non-local reference: {ref}')
            target = spec
            for component in ref[2:].split('/'):
                target = target[component.replace('~1', '/').replace('~0', '~')]
        if value.get('type') == 'object' and 'properties' in value:
            require(set(value.get('required', [])) <= set(value['properties']),
                    'Required property absent from schema')
    operation_ids = set()
    for path, methods in spec['paths'].items():
        expected_parameters = set(re.findall(r'\{([^}]+)\}', path))
        for method, operation in methods.items():
            require(operation['operationId'] not in operation_ids, 'Duplicate operationId')
            operation_ids.add(operation['operationId'])
            parameters = operation.get('parameters', [])
            actual_parameters = {p['name'] for p in parameters if p.get('in') == 'path'}
            require(expected_parameters == actual_parameters, f'Path parameters: {path}')
            require('401' in operation['responses'] and '403' in operation['responses'],
                    f'Missing authorization errors: {path}')
            require(any(code.startswith('2') for code in operation['responses']), 'Missing success')
            if method not in ('get', 'head'):
                require(any(p.get('$ref', '').endswith('/CsrfToken') for p in parameters),
                        f'Missing CSRF requirement: {path}')
            if any(p.get('$ref', '').endswith('/IfMatch') for p in parameters):
                require({'412', '428'} <= operation['responses'].keys(), 'Missing version errors')
    print(f'Contract: {len(operation_ids)} operations, local references and security conventions OK')


def check_fixtures():
    corpus = json.loads((ROOT / 'tests/fixtures/cv/cases.json').read_text())
    require(corpus['synthetic'] is True, 'Fixtures must be synthetic')
    ids = set()
    for case in corpus['cases']:
        require(case['id'] not in ids, 'Duplicate fixture')
        ids.add(case['id'])
        require('input' in case and 'expected' in case, 'Missing fixture data')
        if case['kind'] == 'periods':
            months = set()
            for start, end in case['input']['periods']:
                sy, sm = map(int, start.split('-'))
                ey, em = map(int, end.split('-'))
                require(1 <= sm <= 12 and 1 <= em <= 12, 'Invalid month')
                lo, hi = sy * 12 + sm - 1, ey * 12 + em - 1
                require(lo <= hi, 'Reversed interval')
                months.update(range(lo, hi + 1))
            require(len(months) == case['expected']['months'], f'Incorrect oracle: {case["id"]}')
        if case['kind'] in ('level', 'unknown'):
            require(case['expected']['months'] is None, 'Invented duration')
    print(f'Fixtures: {len(ids)} scenarios; month-union oracles independently checked')


def check_template():
    path = ROOT / 'templates/cv/skillhunters-cv-template-v1.docx'
    with ZipFile(path) as archive:
        names = archive.namelist()
        require(not any('vbaProject' in n for n in names), 'Macros forbidden')
        root = ElementTree.fromstring(archive.read('word/document.xml'))
        ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
        content = '\n'.join(n.text or '' for n in root.findall('.//w:t', ns))
        for token in ('{{candidate.fullName}}', '{{skill.declaredLevel}}', '{{experience.period}}'):
            require(token in content, f'Missing placeholder: {token}')
        require('@' not in content, 'Literal email address in clean template')
        require(not re.search(r'\b(?:19|20)\d{2}\b', content), 'Example year in clean template')
        core = ElementTree.fromstring(archive.read('docProps/core.xml'))
        for element in core:
            if element.tag.endswith(('creator', 'lastModifiedBy')):
                require(not (element.text or '').strip(), 'Personal author metadata')
    print('Template: placeholders, no literal email/year, empty author metadata, no macros')


if __name__ == '__main__':
    check_contract()
    check_fixtures()
    check_template()
    print('L0 artifact checks passed. Runtime, OCR, security integration and benchmark remain untested.')
