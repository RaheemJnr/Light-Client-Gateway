# Google Play Store Listing Checklist

## Text Content (ready)

- [x] App title: `listings/en-US/title.txt`
- [x] Short description (76 chars): `listings/en-US/short-description.txt`
- [x] Full description: `listings/en-US/full-description.txt`

## Graphics (TODO)

- [ ] App icon — replace placeholder with final Pocket Node icon
  - Adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml`
  - Foreground: 108x108dp (432x432px xxxhdpi)
  - Background: solid color or image layer
  - Also provide 512x512 PNG for Play Store

- [ ] Phone screenshots — at least 2, recommended 8
  - Min 320px, max 3840px on any side
  - Capture in both **light** and **dark** mode
  - Suggested screens:
    1. Home (balance + actions)
    2. Send CKB
    3. Receive (QR code)
    4. Transaction history (Activity tab)
    5. Onboarding (create wallet)
    6. Mnemonic backup (word grid)
    7. Node status dashboard
    8. Settings

- [ ] Feature graphic: 1024x500 PNG or JPG
  - Shown at top of Play Store listing
  - Include app name, tagline, and key visual

## Play Console Setup (TODO)

- [ ] Privacy policy — host publicly and provide URL
  - Must cover: data collected (none), key storage, permissions used
  - Suggested host: GitHub Pages or project website

- [ ] Content rating questionnaire
  - App category: Finance
  - No violence, no user-generated content
  - Cryptocurrency wallet functionality

- [ ] Target audience declaration
  - Target age: 18+ (financial app)
  - Not designed for children

- [ ] App category: Finance
- [ ] Contact email for store listing
- [ ] Data safety section
  - No data collected or shared
  - Data encrypted in transit (P2P connections)
  - Data encrypted at rest (TEE/StrongBox)
  - Users can request data deletion (wipe wallet)
