# ADR 0002: Opcjonalne konto i ranking znajomych

Status: przyjęty do dalszej implementacji.

## Kontekst

Podstawowy rytuał FlipFocus ma działać bez konta. Sesja, historia, passa i lokalne statystyki pozostają dostępne offline i nie wymagają wysłania danych na serwer. Funkcje społeczne, w szczególności ranking znajomych, wymagają rozpoznawalnej tożsamości oraz zgody na udostępnianie wyników.

Obecny serwer ma pseudonimową rejestrację tokenem, pokoje znajomych i ranking tygodniowy. Nie ma jeszcze logowania przez Google, Apple ani ręcznego konta.

## Decyzja

- Uruchomienie aplikacji, timer, historia, passa i prywatne statystyki nie wymagają logowania.
- Logowanie pojawia się dopiero przy wejściu do rankingu znajomych, utworzeniu/dołączeniu do pokoju lub włączeniu synchronizacji społecznej.
- Wspieramy trzy metody tożsamości:
  - Google przez weryfikację tokenu ID po stronie serwera;
  - Apple przez weryfikację tokenu Apple po stronie serwera;
  - konto ręczne przez e-mail i hasło, z weryfikacją e-maila oraz resetem hasła.
- Serwer nie przyjmuje od aplikacji haseł Google/Apple i nie ufa samemu identyfikatorowi użytkownika z klienta. Łączy konto z `(provider, provider_subject)` po zweryfikowaniu tokenu.
- Jedno konto może mieć kilka metod logowania. Łączenie metod wymaga aktywnej, uwierzytelnionej sesji i ponownego potwierdzenia drugiej metody.
- Token sesji jest przechowywany wyłącznie w natywnym secure storage. Lokalny snapshot skupienia nie zawiera konta, tokenu ani danych rankingu.
- Statystyki lokalne nie są automatycznie wysyłane po zalogowaniu. Do rankingu trafiają wyłącznie ukończone minuty bieżącego tygodnia, po osobnej zgodzie konta i pokoju.
- Wylogowanie odłącza konto i zatrzymuje synchronizację społeczną, ale nie usuwa historii ani statystyk lokalnych.
- Usunięcie konta usuwa serwerowe dane osobowe, członkostwa i wkład do rankingu; dane lokalne pozostają na urządzeniu do czasu osobnego usunięcia historii.

## Kontrakt produktu

Stan bez logowania:

```text
timer -> historia -> passa -> statystyki lokalne
```

Stan wymagający logowania:

```text
ranking znajomych -> konto -> zgoda na ranking -> pokój znajomych
```

Brak sieci lub wylogowanie nie może przerwać aktywnej sesji ani usunąć lokalnego postępu. Ranking pokazuje wyłącznie osoby, które wyraziły zgodę na ranking na poziomie konta i pokoju.

## Konsekwencje i kolejne etapy

1. Wprowadzić serwerowy model `auth_identities`, sesje odświeżane i jednorazowe endpointy logowania.
2. Dodać port platformowy `AuthProvider` w `composeApp`, z implementacją Google i Apple dla Androida/iOS.
3. Dodać ręczne logowanie przez e-mail bez przechowywania haseł w aplikacji; backend powinien używać sprawdzonego hashowania haseł, np. Argon2id.
4. Zastąpić obecne `register + bearer token` migracją kont pseudonimowych albo jawnie oznaczyć je jako konto pilotażowe do migracji.
5. Dodać testy: aplikacja bez konta, blokada rankingu bez konta, zgody rankingu, wylogowanie bez utraty historii, odświeżanie tokenu i usunięcie konta.

Nie wdrażamy własnego OAuth ani przechowywania haseł w KMP. Weryfikacja dostawców i polityka sesji należą do backendu lub dedykowanej usługi tożsamości.