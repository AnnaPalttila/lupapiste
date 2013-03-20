*** Settings ***

Documentation   Mikko creates a new inforequest and then an application from it.
Suite teardown  Logout
Resource        ../../common_resource.robot

*** Test Cases ***

Mikko creates a new inforequest
  Mikko logs in
  Create inforequest the fast way  create-app-from-info  753  75341600250030  Jiihaa

There are no attachments at this stage
  Element should not be visible  xpath=//*[@data-test-id='inforequest-attachments-table']
  Element should be visible  xpath=//*[@data-test-id='inforequest-attachments-no-attachments']

Mikko creates new application from inforequest
  Click by test id  inforequest-convert-to-application
  Wait until  Element should be visible  application
  Wait until  Element Text Should Be  xpath=//span[@data-test-id='application-property-id']  75341600250030
#  Permit type should be  Rakennuslupahakemus

Proper attachment templates are present
  Open tab  attachments
  Wait until  Element should be visible  xpath=//tr[@class='attachment-group-header']
  # Element should not be visible  xpath=//*[@data-test-id='application-attachments-no-attachments']

Mikko closes application
  Close current application
  Wait Until  Element should be visible  applications
