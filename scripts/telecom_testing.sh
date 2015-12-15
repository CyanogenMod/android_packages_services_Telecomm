lite_test_telecom() {
  usage="
  Usage: lite_test_telecom [-c CLASSNAME] [-d] [-i] [-e], where

  -c CLASSNAME          Run tests only for the specified class/method. CLASSNAME
                          should be of the form SomeClassTest or SomeClassTest#testMethod.
  -d                    Waits for a debugger to attach before starting to run tests.
  -i                    Rebuild and reinstall the test apk before running tests
  -e                    Run code coverage. Coverage will be output into the coverage/
                          directory in the repo root.
  "

  OPTIND=1
  class=
  install=false
  debug=false
  coverage=false

  while getopts "c:die" opt; do
    case "$opt" in
      \?)
        echo "$usage"
        return 0;;
      c)
        class=$OPTARG;;
      d)
        debug=true;;
      i)
        install=true;;
      e)
        coverage=true;;
    esac
  done

  T=$(gettop)

  if [ $install = true ] ; then
    olddir=$(pwd)
    cd $T
    if [ $coverage = true ] ; then
      emma_opt="EMMA_INSTRUMENT_STATIC=true"
    else
      emma_opt="EMMA_INSTRUMENT_STATIC=false"
    fi
    ANDROID_COMPILE_WITH_JACK=false mmm "packages/services/Telecomm/tests" ${emma_opt}
    adb install -r -t "out/target/product/$TARGET_PRODUCT/data/app/TelecomUnitTests/TelecomUnitTests.apk"
    if [ $? -ne 0 ] ; then
      cd "$olddir"
      return $?
    fi
    cd "$olddir"
  fi

  e_options=""
  if [ -n "$class" ] ; then
    e_options="${e_options} -e class com.android.server.telecom.tests.${class}"
  fi
  if [ $debug = true ] ; then
    e_options="${e_options} -e debug 'true'"
  fi
  if [ $coverage = true ] ; then
    e_options="${e_options} -e coverage 'true'"
  fi
  adb shell am instrument ${e_options} -w com.android.server.telecom.tests/android.test.InstrumentationTestRunner

  if [ $coverage = true ] ; then
    adb pull /data/user/0/com.android.server.telecom.tests/files/coverage.ec /tmp/
    java -cp external/emma/lib/emma.jar emma report -r html -sp packages/services/Telecomm/src -in out/target/common/obj/APPS/TelecomUnitTests_intermediates/coverage.em -in /tmp/coverage.ec
  fi
}
