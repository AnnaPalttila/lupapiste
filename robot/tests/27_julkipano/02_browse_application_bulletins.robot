*** Settings ***

Documentation   User browses bulletins from Julkipano.fi bulletin list
Suite Setup     Apply minimal fixture now
Suite Teardown  Logout
Resource        ../../common_resource.robot
Resource        ./27_common.robot

*** Test Cases ***

Bulletins should be paginable
  Create bulletins  11
  Go to bulletins page

  Bulletin list should have rows  10
  Bulletin button should have bulletins left to fetch  1

  Load more bulletins

  Bulletin list should have rows  11

Bulletins should be searchable
  As Sonja
  Create application and publish bulletin  Mixintie 15  753-416-25-22
  Go to bulletins page

  Search bulletins by text  Mixintie 15
  Wait Until  Bulletin list should have rows and text  1  Mixintie 15

Velho adds some bulletins
  As Olli
  Create application and publish bulletin  Vaalantie 540  564-404-26-102
  Go to bulletins page

Bulletins should be filterable by municipality
  Autocomplete option list should contain by test id  municipalities-filter-component  Koko Suomi  Oulu  Sipoo

  Select From Autocomplete By Test Id  municipalities-filter-component  Oulu
  Wait for jQuery
  Wait Until  Bulletin list should have rows and text  10  Vaalantie 540
  # Two bulletins left for Oulu filter, three bulletins left without filter (Sipoo has 1)
  Bulletin button should have bulletins left to fetch  2

  Select From Autocomplete By Test Id  municipalities-filter-component  Sipoo
  Wait Until  Bulletin list should have rows and text  1  Mixintie 15

Bulletins should be filterable by state
  Reload Page
  Autocomplete option list should contain by test id  states-filter-component  Kaikki ilmoitustilat  Kuulutettavana

  Select From Autocomplete By Test Id  states-filter-component  Kuulutettavana
  Wait Until  Bulletin list should have rows  10
  Bulletin button should have bulletins left to fetch  3
