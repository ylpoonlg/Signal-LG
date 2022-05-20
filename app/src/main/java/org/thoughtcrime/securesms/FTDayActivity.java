package org.thoughtcrime.securesms;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import org.thoughtcrime.securesms.R;

public class FTDayActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_ftday);

    com.google.android.material.floatingactionbutton.FloatingActionButton backButton = findViewById(R.id.ftday_back_btn);
    backButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) {
        finish();
      }
    });
  }
}