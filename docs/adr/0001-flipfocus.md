# ADR 0001: FlipFocus, wspólny rdzeń i lokalna prywatność

Status: przyjęty do implementacji na podstawie polecenia użytkownika z 2026-09-21.

Katalog zawierał wyłącznie plany i historyczne memories quizu, bez kodu ani repozytorium Git. FlipFocus jest jedynym aktywnym produktem (opcja A); historyczne pliki pozostają niezmienione. Identyfikator aplikacji: `pl.flipfocus.app`. Właścicielem konfiguracji wydania pozostaje właściciel projektu.

Moduły: `shared:domain` (reguły), `shared:data` (lokalna persystencja), `shared:presentation` (MVI), `composeApp` (wspólne Compose UI i adaptery), `iosApp` (host). Domena nie importuje UI ani storage. Zależności składane jawnie w hostach. Dodatkowy target desktop służy szybkiej weryfikacji i podglądowi.

Domyślnie: 25 minut, Grace Period 10 sekund, minimum do zaliczenia dnia 15 minut, własny czas 1–180 minut. Ustawienia aktywnej sesji są zamrożone. Sesja zaczyna się po stabilnym pomiarze Screen-Down w widoku gotowości. Dzień zalicza ukończona sesja według daty jej zakończenia w lokalnej strefie czasu. Anulowana sesja nie zalicza dnia. Pełna historia jest dostępna bez opłat.

Czas skupienia opiera się na zegarze monotonicznym i tylko potwierdzonym pomiarze. Przerwa w pomiarze, tło i restart nie generują fikcyjnego czasu. Aplikacja nie blokuje ręcznej blokady ekranu; podczas aktywnej sesji zapobiega automatycznemu uśpieniu. iOS nie gwarantuje działania Core Motion w tle, więc oba hosty wstrzymują pomiar i rozpoczynają ograniczone okno powrotu. Jest to jawny kompromis względem ciągłej pracy po blokadzie; wymaga walidacji na urządzeniach.

Lokalny zapis jest wersjonowany, atomowy i posiada ostatnią poprawną generację. Historia jest deduplikowana według ID sesji. Usunięcie danych obejmuje również kopię odzyskiwania. Nie wysyłamy sensorów, historii ani rytmu dnia. Analityka jest dobrowolna i lokalna. LIVE, ranking i powiadomienia domyślnie wyłączone.

Źródła UI: Figma `Uvw2EdJMOEZ5XV7JagGpmd`, frame’y `2:442`, `2:478`, `2:512`, `2:1055`. Natywne status/navigation bars zastępują makietowe elementy systemowe; touch targets powiększone do 48 dp. Liczby przykładowych znajomych z Figmy nie stanowią danych użytkownika.

Fazy beta, wdrożenie usług społecznościowych i sprzedaż wymagają rzeczywistych uczestników, infrastruktury i konfiguracji sklepów. Kod i scenariusze można przygotować lokalnie, ale nie oznacza to przeprowadzonej bety, retencji, publikacji lub zatwierdzonego modelu biznesowego. Nie udostępniamy danych ani nie pobieramy płatności bez skonfigurowanej usługi.
