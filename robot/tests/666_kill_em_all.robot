*** Settings ***

Documentation  Kill all browsers
Resource       ../common_resource.robot

*** Test Cases ***

Close all browsers
  [Tags]  ie8
  Close all browsers

Close all browsers
  [Tags]  integration
  Close all browsers
