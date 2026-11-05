# Etiket - Online-first starter catalogue

Prepared 04 November 2026.

## What was actually collected

- 78 selected public Bazarstore pages were checked, producing 77 unique product records.
- All 77 captured barcode strings passed a format/check-digit check. This does not certify GS1 ownership or formulation accuracy.
- 10 product pages supplied ingredient text. The other 67 have no Bazarstore ingredient text in the captured public page and remain explicitly incomplete.
- One additional cross-market ingredient candidate was researched for the 7 Days croissant in the user's screenshot. It is stored separately and is not verified.
- The sitemap reported 15,415 URLs. These include category, brand and other pages; that number is NOT a product count.
- This is a SAMPLE, not the whole Bazarstore product catalogue. Missing web text does not mean a product has no ingredients.

## Files

- `catalogue.json`: source-linked product facts, ingredient candidates, review states and gaps.
- `discovery_urls.txt`: all URLs returned by the public sitemap during discovery; unclassified and not all products.
- `collect_sample.py`: the small rate-limited sampler used for the initial extraction. Requires Python and lxml. It respects the public robots rules and stops on HTTP 403/429. It deliberately samples food pages.

## How to use this in Stage 1

1. Use the 10 records with ingredient text as provisional test data for a barcode-to-product lookup.
2. Keep `WEB_SOURCED_UNVERIFIED` visible. These records were checked against retailer web pages, not current physical packages.
3. Use the 67 incomplete records to design the known-product/missing-ingredients workflow.
4. Start backend and mobile setup in parallel. Building an entire catalogue is not a prerequisite to writing the application.
5. Add more records through reviewed online sources or a retailer/manufacturer feed. Match exact barcode, variant, market and pack size; record conflicts instead of inventing a resolution.
6. Collect a few real package photographs when implementing camera scanning and label OCR. Expand physical label checks before marking records package-verified.

## Why photographs are useful later

- A front image helps shoppers recognise a product; a placeholder works in the first prototype.
- A barcode photograph or real package tests the camera against bars, lighting and curvature. A web page's printed barcode digits alone are not a camera barcode test.
- An ingredient/nutrition photograph provides label evidence and input for OCR. It can reveal spelling, percentages, allergens or formulations missing from a website.
- Online product artwork is not an OCR test image unless the actual ingredient panel is present and readable.

## Data quality and source conflicts

The croissant barcode `5201360553457` is listed as 65 g on Bazarstore and PRODLY, but a ROZETKA listing uses 60 g. A second Bazarstore example has 680 g in its current title and 660 g in its URL slug. Neither a matching barcode nor a historical URL proves that every field is current. The conflicts are retained in the JSON.

The raw ingredient section is retained separately from the cleaned ingredient candidate. The cleaner only removes obvious trailing GMO statements and punctuation; it is not a substitute for review. Nutrition and dietary/allergy claims have not been validated or inferred by this dataset.

## Sources and reuse

Every product has a source URL and capture time. No image files, website marketing descriptions, customer information or contact directories are packaged here. Product-image URLs are references only. Commercial bulk reuse and image permissions have not been established; plan an agreed retailer/manufacturer feed or otherwise appropriate source arrangement for a production catalogue.

The cross-market candidate is based on these retailer product records:

- https://bazarstore.az/7-days-midi-kruassan-65-q-bismis-sudlu
- https://prodly.kz/product/604e3aef18912e2f59c54179?category=326HQn6piU917uuLKWzXK8Dicg4
- https://rozetka.com.ua/338010565/p338010565/

## Ten provisional ingredient examples

| Product | Barcode | Ingredient candidate (Azerbaijani) | Source |
|---|---|---|---|
| N1 ŞOR 400 Q QAYMAQLI | `4760177560085` | inək südü, bitki yağı, maya, duz, qara çörək otu | [Bazarstore](https://bazarstore.az/n1-sor-400-q-qaymaqli) |
| AZERSÜD KEFİR 1 L | `4760095105863` | Pasterizə inək südü,maya | [Bazarstore](https://bazarstore.az/azersud-kefir-1-l) |
| ERMI YOQURT İÇMƏLİ 290 Q ÇİYƏLƏK BANAN | `4606779682407` | yağsızlaşdırılmış süd, su, qaymaq, şəkər, qlükoza-fruktoza siropu, quru süd zərdabı, çiyələk püresi, çiyələk şirəsi,banan püresi, qatılaşdırıcılar (E1422, ksantan kitrəsi, pektinlər), jelatin, aromatizatorlar, turşuluq tənzimləyiciləri (limon turşusu, natrium sitratlar, natrium fosfatlar), boyayıcı - karminlər, mayadan istifadə etməklə | [Bazarstore](https://bazarstore.az/ermi-yoqurt-icmeli-290-q-ciyelek-banan) |
| ERMI YOQURT İÇMƏLİ 290 Q MEŞƏ GİLƏMEYVƏ | `4606779682100` | yağsızlaşdırılmış süd, su, şəkər, qlükoza-fruktoza siropu,qaymaq,quru süd zərdabı,konsentrasiyalı çiyələk püresi,konsentrasiyalı moruq püresi, qaragilə, mərsin, qatılaşdırıcılar(E1442,E1422,pektinlər,ksantan kitrəsi),jelatin, konsentrasiyalı yerkökü,üzüm vəaroniya şirələri, aromatizatorlar, turşuluq tənzimləyiciləri (limon turşusu, natrium sitratlar, kalsium sitratlar,natrium fosfatlar), mayadan istifadə etməklə | [Bazarstore](https://bazarstore.az/ermi-yoqurt-icmeli-290-q-mese-gileme-1) |
| BIZIM TARLA ZOĞAL MÜRƏBBƏSİ 400 Q | `4760018505107` | zoğal,şəkər tozu | [Bazarstore](https://bazarstore.az/bizim-tarla-zogal-murebbesi-400-q) |
| BİZİM TARLA YAŞIL NOXUD 680 Q | `4760065708018` | yaşıl noxud,pasterizə edilmiş su, duz, şəkər tozu, turşuluq tənzimləyicisi:limon turşusu | [Bazarstore](https://bazarstore.az/bizim-tarla-yasil-noxud-660-q) |
| BİZİM TARLA ÜZÜM YARPAĞI 640 Q | `4760068002243` | üzüm yarpağı,pasterilizə edilmiş su,duz | [Bazarstore](https://bazarstore.az/bizim-tarla-uzum-yarpagi-640-q) |
| BIZIM TARLA TOMAT PASTASI 720 Q | `4760065703150` | Pomidor,turşuluq tənzimləyicisi:limon turşusu | [Bazarstore](https://bazarstore.az/bizim-tarla-tomat-pastasi-720-q) |
| BARILLA MAKARONA 450 Q TORTIGLIONI | `8076809576130` | əla növ bərk buğda unu, su | [Bazarstore](https://bazarstore.az/barilla-makarona-450-q-tortiglioni) |
| BİZİM TARLA SOYULMUŞ POMİDOR 640 Q | `4760065708957` | Pomidor,duz | [Bazarstore](https://bazarstore.az/bizim-tarla-soyulmus-pomidor-640-q) |
