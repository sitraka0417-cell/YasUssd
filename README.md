# Yas USSD
App Android (Kotlin, sans bibliothèque externe).

- Accueil épuré : le solde (`#357#`) et le forfait restant de l'offre choisie s'affichent directement, mis à jour automatiquement en arrière-plan.
- Offres : la liste est lue automatiquement (`#359#` puis `1`), puis un appui sur une offre envoie `1 → numéro de l'offre → 1` et affiche le forfait restant.
- Paramètres (icône engrenage) : SIM, accessibilité, codes, rappel de fin de forfait, code libre, autorisations.

Build : GitHub Actions (onglet Actions > Build APK > Artifacts > YasUssd-debug-apk).
