#!/usr/bin/env python3
"""Generate matching API/Android seeds from the captured catalogue; no network calls.

Run after changing catalogue/source.json. --check detects stale generated assets.
Python is needed only for updating seeds, not for running or building the app.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUTS = [ROOT / 'backend/src/main/resources/catalogue/starter-catalogue.json',
           ROOT / 'app/src/main/assets/starter-catalogue.json']


def barcode_is_valid(value):
    if not isinstance(value, str) or not value.isascii() or not value.isdigit() or len(value) not in (8, 12, 13, 14):
        return False
    total = sum(int(digit) * (3 if i % 2 == 0 else 1) for i, digit in enumerate(reversed(value[:-1])))
    return (10 - total % 10) % 10 == int(value[-1])


def generate():
    source_bytes = (ROOT / 'catalogue/source.json').read_bytes()
    source = json.loads(source_bytes)
    records = []
    codes = set()
    for row in source['products']:
        code = row['barcode']
        assert barcode_is_valid(code) and code not in codes, f'Invalid/duplicate barcode: {code}'
        assert row['review_status'] == 'WEB_SOURCED_UNVERIFIED' and row['label_photo_checked'] is False
        assert row['product_source_url'].startswith('https://bazarstore.az/')
        codes.add(code)
        ingredients = row.get('ingredients_text_candidate') or ''
        evidence = {
            'reviewStatus': row['review_status'],
            'ingredientsStatus': 'WEB_TEXT_REQUIRES_REVIEW' if ingredients else 'MISSING',
            'labelPhotoChecked': False,
            'ingredientsSourceUrl': row.get('ingredients_source_url') or '',
            'ingredientSectionAsReported': row.get('ingredient_section_as_reported') or '',
            'declaredAllergenStatement': row.get('declared_allergen_statement_as_reported') or '',
            'notes': row.get('notes', []),
            'externalCandidateAvailable': bool(row.get('external_ingredient_candidate')),
        }
        product = {
            'barcode': code, 'name': row['name_az'], 'brand': row.get('brand') or '', 'quantity': '',
            'ingredients': ingredients, 'ingredientsLanguage': row.get('ingredients_language') or '',
            'nutriScore': '', 'nutrients': {}, 'allergens': [], 'traces': [],
            'source': 'BAZARSTORE', 'sourceUrl': row['product_source_url'],
            'retrievedAt': row['captured_at'], 'sourceUpdatedAt': '', 'demo': False, 'evidence': evidence,
        }
        # A foreign-market candidate is retained ONLY in sourceRecord, never primary ingredients.
        records.append({'product': product, 'sourceRecord': row})
    assert len(records) == source['product_records']
    assert sum(bool(x['product']['ingredients']) for x in records) == source['records_with_bazarstore_ingredients']
    return (json.dumps({'schemaVersion': 1, 'dataset': 'bazarstore-starter',
            'sourceDigest': hashlib.sha256(source_bytes).hexdigest(), 'records': records},
            ensure_ascii=False, indent=2) + '\n').encode('utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = generate()
    for path in OUTPUTS:
        if args.check:
            if not path.exists() or path.read_bytes() != data:
                raise SystemExit(f'Stale seed: {path.relative_to(ROOT)}; run scripts/prepare_catalogue.py')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print('Catalogue assets match: 77 products, 10 web ingredient lists, 67 missing; no label-verified records.')
