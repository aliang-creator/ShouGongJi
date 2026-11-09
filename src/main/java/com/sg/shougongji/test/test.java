package com.sg.shougongji.test;

public class test {
    public static void main(String[] args) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(5000);
                System.out.println("worker 执行完了");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        worker.setDaemon(false); // false 表示非守护线程
        worker.start();
        System.out.println("main 执行完了");
    }
}
