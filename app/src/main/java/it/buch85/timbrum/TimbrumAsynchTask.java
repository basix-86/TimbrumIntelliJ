package it.buch85.timbrum;

import android.os.AsyncTask;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import it.buch85.timbrum.prefs.TimbrumPreferences;
import it.buch85.timbrum.request.LoginResult;
import it.maverick.workday.Clock;
import it.maverick.workday.InvalidClockingSequenceException;
import it.maverick.workday.Workday;

/**
 * Created by Marco on 19/08/2014.
 */

//todo
public class TimbrumAsynchTask extends AsyncTask<TimbrumAsynchTask.Action, Integer, Report> {
    private final Timbrum timbrum;
    private final TimbrumPreferences preferences;
    private final TimbrumView timbrumView;
    private RemoteClock remoteClock;
    private Workday workday;
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GTM+00"));

    public TimbrumAsynchTask(TimbrumPreferences preferences, TimbrumView timbrumView) {
        this.preferences = preferences;
        this.timbrumView = timbrumView;
        remoteClock = new RemoteClock();

        long timeToWorkMillis = preferences.getTimeToWork();
        workday = new Workday(remoteClock, millisToMinutes(timeToWorkMillis));

        calendar.setTimeInMillis(preferences.getLunchtimeStart());
        int startH = calendar.get(Calendar.HOUR_OF_DAY);
        int startM = calendar.get(Calendar.MINUTE);

        calendar.setTimeInMillis(preferences.getLunchtimeEnd());
        int endH = calendar.get(Calendar.HOUR_OF_DAY);
        int endM = calendar.get(Calendar.MINUTE);

        workday.setLunchBreak(startH, startM, endH, endM, millisToMinutes(preferences.getMinLunchtimeDuration()));
        workday.setMinimumBreak(millisToMinutes(preferences.getMinPauseDuration()));

        timbrum = new Timbrum(preferences.getHost(), preferences.getUsername(), preferences.getPassword());
    }

    private int millisToMinutes(long millis) {
        return (int) (millis / (Workday.MILLISECONDS * Workday.SECONDS_IN_A_MINUTE));
    }


    @Override
    protected Report doInBackground(Action... actions) {
        Action action = actions[0];
        try {
            publishProgress(R.string.logging_in);
            LoginResult loginResult = timbrum.login();
            if (!loginResult.isSuccess()) {
                timbrumView.setLoginError(loginResult.getMessage());
                return null;
            }
            Date now = timbrum.now();
            remoteClock.setRemoteTime(now);
            timbrumView.setNow(now);
            publishProgress(R.string.loading_logs);
            Report report = timbrum.getReport(remoteClock.now);
            if (action.isPunching()) {
                if (!report.isNextTimbrumValid(action.versoTimbratura)) {
                    boolean confirmed = timbrumView.askTimbrumConfirmation(action.versoTimbratura);
                    if (!confirmed) {
                        return report;
                    }
                }
                publishProgress(R.string.loading_workspace);
                String timbraturaId = timbrum.getTimbraturaId();
                publishProgress(R.string.timbrum_in_progress);
                timbrum.timbra(action.versoTimbratura, timbraturaId);
                publishProgress(R.string.loading_logs);
                return timbrum.getReport(remoteClock.now);
            }
            return report;
        } catch (Exception e) {
            timbrumView.setErrorMessage(e.getMessage());
            return null;
        }
    }

    @Override
    protected void onProgressUpdate(Integer... resourceIds) {
        for (Integer resourceId : resourceIds) {
            timbrumView.updateProgress(resourceId);
        }
    }

    @Override
    protected void onPreExecute() {
        timbrumView.resetView();
        timbrumView.initProgress();
    }

    @Override
    protected void onPostExecute(Report result) {
        timbrumView.dismissProgress();
        if (result == null) {
            timbrumView.showErrorMessage();
            return;
        }
        try {
            updateWorkday(result);
            timbrumView.updateView(result, workday);
        } catch (InvalidClockingSequenceException e) {
            timbrumView.updateView(result);
        }
    }

    private void updateWorkday(Report result) throws InvalidClockingSequenceException {
        for (RecordTimbratura recordTimbratura : result.getTimbrature()) {
            if (recordTimbratura.isEntry()) {
                workday.addClockingIn(recordTimbratura.getTimeFor(remoteClock.now));
            } else {
                workday.addClockingOut(recordTimbratura.getTimeFor(remoteClock.now));
            }
        }
    }

    private class RemoteClock implements Clock {
        Date now;

        public void setRemoteTime(Date now) {
            this.now = now;
        }

        @Override
        public long getTimeNow() {
            return now.getTime();
        }
    }

    public enum Action {
        REFRESH(null), ENTER(VersoTimbratura.ENTRATA), EXIT(VersoTimbratura.USCITA);
        private VersoTimbratura versoTimbratura;

        Action(VersoTimbratura versoTimbratura) {
            this.versoTimbratura = versoTimbratura;
        }

        public boolean isPunching() {
            return versoTimbratura != null;
        }
    }

    public void refresh() {
        execute(Action.REFRESH);
    }

    public void enter() {
        execute(Action.ENTER);
    }

    public void exit() {
        execute(Action.EXIT);
    }


}
