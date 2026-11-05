# Ingredient explanations — 0.3

## What is described

`IngredientExplainer` in the shared `core` module describes exact aliases in Azerbaijani source ingredient lists. It never reads the product name, foreign candidates, nutrients or allergen fields to invent ingredients. `Product.ingredients` and `ProductEvidence` remain the source records. A dictionary match is not package verification.

The wording is `EDITORIAL_DRAFT`. Common food descriptions are short editorial definitions. Additive names and functions use the primary references below. A competent Azerbaijani editor and food specialist should review this wording before public release; no such expert review is claimed by this increment.

## API contract

The additive `ingredientAnalysis` object appears on product lookups, catalogue pages and fictional fixtures:

```json
{
  "status": "AVAILABLE",
  "language": "az",
  "dictionaryVersion": "2026-11-v1",
  "contentStatus": "EDITORIAL_DRAFT",
  "recognizedCount": 1,
  "items": [{
    "reportedText": "bitki yağı",
    "group": "",
    "recognized": true,
    "nameAz": "Bitki yağı",
    "explanationAz": "Bitki mənşəli yağdır. Mənbə mətni konkret bitkini göstərmir.",
    "references": []
  }]
}
```

| Status | Meaning |
| --- | --- |
| `AVAILABLE` | Every parsed entry has a dictionary description; this does not imply the source list is complete or verified. |
| `PARTIAL` | Some entries are recognised. Unknown entries retain their reported text and an empty explanation. |
| `NO_MATCHES` | A non-empty Azerbaijani list has no known exact aliases. |
| `MISSING` | No source ingredient list; there are no analysis items. |
| `UNSUPPORTED_LANGUAGE` | The reported list is not tagged Azerbaijani; there are no translated guesses. |

The output language is Azerbaijani even when the interface is switched to English. The UI labels this explicitly.

## Matching and compatibility

- Matching normalises Unicode, Azerbaijani case, whitespace, E-number spacing and optional trailing percentages. There is no substring or fuzzy match: `süd yoxdur` does not match `süd`.
- Commas and semicolons split top-level entries while preserving brackets and decimal commas. Only named functional groups such as `qatılaşdırıcılar (...)` are expanded. Malformed or unknown groups remain unknown text.
- Entries remain in source order. Quantities and allergen implications are not inferred from dictionary definitions.
- Derived analysis is recomputed from the raw text when a Product is loaded. Existing database payloads and 0.2 API responses do not need the new property. Android persists the original product data and regenerates these general descriptions on reopening it.
- Backend and Android use the same Java implementation. They must be rebuilt for a dictionary change. A future editorial database would need versioned publication and synchronisation; it is not implemented here.

## References consulted

- [FDA — Types of Food Ingredients](https://www.fda.gov/food/food-additives-and-gras-ingredients-information-consumers/types-food-ingredients): functions of sweeteners, flavours, emulsifiers and texturisers.
- [Food Standards Australia New Zealand — What do food additives do?](https://www.foodstandards.gov.au/consumer/additives/additiveinfo): functional descriptions of acidity regulators, emulsifiers and thickeners.
- [UK Food Standards Agency — Additive names and E numbers](https://www.gov.uk/government/publications/approved-additives-and-e-numbers/approved-additives-and-e-numbers): additive identities, including E120, E330–E339, E415, E440, E1422 and E1442. These references do not assert Azerbaijani regulatory authorisation.
- [FAO / JECFA — Edible Gelatin](https://www.fao.org/fileadmin/user_upload/jecfa_additives/docs/Monograph1/Additive-171.pdf): gelatin's collagen origin. The product source does not identify the animal species.

Add new aliases only after checking the complete reported phrase. Preserve ambiguity, include a supporting primary reference for specialised claims, update the dictionary version, and test both a positive example and a phrase that must remain unknown.
