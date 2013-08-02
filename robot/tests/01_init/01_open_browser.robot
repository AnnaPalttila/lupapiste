*** Settings ***

Documentation   Open browser
Resource        ../../common_resource.robot

*** Test Cases ***

The shared browser is opened
  [Tags]  ie8
  Open browser to login page

The shared browser is opened for integration tests
  [Tags]  integration
  Open browser to login page

Login page is in Finnish
  [Tags]  ie8
  Page Should Not Contain  Suomeksi
  Page Should Contain  På svenska
