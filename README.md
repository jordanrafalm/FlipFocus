# FlipFocus

FlipFocus to aplikacja pomagająca ograniczyć rozpraszanie przez telefon i budować
nawyk regularnego skupienia. Jest przeznaczona dla osób, które chcą poświęcić
czas na pracę, naukę lub inne zadanie bez ciągłego sięgania po urządzenie.

Użytkownik wybiera długość sesji i odkłada telefon ekranem w dół. Aplikacja
rozpoznaje jego położenie i uruchamia licznik. Podniesienie telefonu wstrzymuje
skupienie; ponowne odłożenie go w czasie wyznaczonym na powrót pozwala
kontynuować sesję.

## Funkcje

- Sesje skupienia o długości od 1 do 180 minut oraz gotowe propozycje czasu.
- Historia sesji, dzienne postępy i passy skupienia.
- Przerwy, przypomnienia oraz opcjonalne dźwięki i wibracje.
- Interfejs w języku polskim i angielskim.
- Opcjonalna społeczność: clany, pokoje, zaproszenia i rankingi.
- Opcjonalny status na żywo, zgłaszanie nadużyć oraz usuwanie konta społecznościowego.
- Informacja o przerwaniu skupienia na ekranie blokady: powiadomienie na Androidzie
  lub Live Activity na iOS.

Podstawowe funkcje skupienia nie wymagają konta. Historia i ustawienia są
przechowywane na urządzeniu, a korzystanie z funkcji społecznościowych jest
oddzielne i dobrowolne.

## Architektura

Aplikacja wykorzystuje **Kotlin Multiplatform** do współdzielenia logiki oraz
**Compose Multiplatform** do budowania interfejsu. Wspólny kod jest oddzielony
od integracji z funkcjami systemowymi Androida i iOS.

### Moduły

- **`shared/domain`** — modele i reguły aplikacji, w tym przebieg sesji,
  wykrywanie położenia telefonu, obliczanie postępów oraz kontrakty społeczności.
- **`shared/data`** — lokalny zapis danych i implementacje repozytoriów, w tym
  dostęp do Firebase dla funkcji społecznościowych.
- **`shared/presentation`** — zarządzanie stanem ekranu, obsługa intencji
  użytkownika i przekazywanie efektów do warstwy interfejsu.
- **`composeApp`** — wspólny interfejs Compose oraz adaptery platformowe,
  m.in. dla czujników, przypomnień i powiadomień.
- **`iosApp`** — natywny host iOS, integracja Google Sign-In i rozszerzenie
  Live Activity.

Lokalne repozytorium przechowuje sesję, ustawienia i historię wraz z kopią
odzyskiwania. Warstwa prezentacji korzysta z repozytoriów i przekazuje interfejsowi
stan aplikacji, natomiast czujniki i inne funkcje urządzenia są obsługiwane przez
adaptery właściwe dla danej platformy.

Opcjonalne funkcje społecznościowe korzystają z **Firebase Authentication**
i **Cloud Firestore**; katalog firm jest dostępny lokalnie. Więcej informacji
o decyzjach architektonicznych znajduje się w katalogu
[`docs/adr`](docs/adr).
