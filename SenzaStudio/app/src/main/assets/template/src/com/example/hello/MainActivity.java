package com.example.hello;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private int taps = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView title = (TextView) findViewById(R.id.title);
        Button btn = (Button) findViewById(R.id.btn_hello);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                taps++;
                title.setText("Ketukan ke-" + taps);
                Toast.makeText(MainActivity.this, "Dibuat dengan Senza Studio", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
