# Yas USSD 3.0 (v4)
App Android (Kotlin, sans bibliothèque externe).

- **Accueil** : cartes *Solde* (`#357#`) et *forfait restant*, puis la liste des offres juste dessous (un appui sur une offre la sélectionne).
  Le texte affiché est la copie du résultat de l'opérateur ; appui long = copier.
- **Mise à jour automatique** à chaque ouverture de l'appli (même si elle était restée en arrière-plan), au plus
  une fois toutes les 20 s ; boutons ↻ pour forcer. En cas d'échec, le dernier bon résultat reste affiché avec un avertissement.
- **Arrière-plan** : le solde passe par l'API Android (aucune fenêtre). Pour le forfait, un écran de chargement recouvre
  la fenêtre USSD du système pendant que le service d'accessibilité tape les réponses tout seul.
- **Paramètres** : SIM, accessibilité, codes, rappel de fin de forfait, code libre, **journal technique** (à copier si un code échoue).

Build : GitHub Actions (onglet Actions > Build APK > Artifacts > YasUssd-debug-apk).
Après installation : activer « Yas USSD » dans Paramètres > Accessibilité (à refaire si Android le désactive après une mise à jour).
