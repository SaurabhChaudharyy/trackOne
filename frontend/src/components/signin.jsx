"use client";
import {
  CardTitle,
  CardDescription,
  CardHeader,
  CardContent,
  Card,
} from "@/components/ui/card";
import { TabsTrigger, TabsList, TabsContent, Tabs } from "@/components/ui/tabs";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useState } from "react";
import axios from "axios";

export default function SignInComponent() {
  const [signupData, setSignupData] = useState({ email: "", password: "" });
  const [signinData, setSigninData] = useState({ email: "", password: "" });

  const handleChangeSignup = (e) => {
    setSignupData({ ...signupData, [e.target.id]: e.target.value });
  };

  const handleChangeSignin = (e) => {
    setSigninData({ ...signinData, [e.target.id]: e.target.value });
  };

  const submitHandlerSignup = async () => {
    try {
      const response = await axios.post(
        "http://localhost:8000/api/user/signup",
        {
          email: signupData.email,
          password: signupData.password,
        }
      );
      alert(response.data.message);
    } catch (error) {
      if (error.response) {
        alert(error.response.data.message);
      } else if (error.request) {
        console.error("No response received:", error.request);
        alert("No response from server.");
      } else {
        console.error("Error setting up request:", error.message);
        alert("An error occurred.");
      }
    }
  };

  const submitHandlerSignin = async () => {
    try {
      const response = await axios.post(
        "http://localhost:8000/api/user/login",
        {
          email: signinData.email,
          password: signinData.password,
        }
      );
      const data = response.data;
      alert(data.message);
      if (data.token) {
        window.location.href = "/home";
        const userId = data.userId;
        if (userId) {
          localStorage.setItem("userId", userId);
          localStorage.setItem("authToken", data.token);
        } else {
          console.error("userId not found in response:", data);
        }
      } else {
        console.log("Signin failed:", data);
        alert(data.message);
      }
    } catch (error) {
      if (error.response) {
        console.error("Signin failed:", error.response.data);
        alert(error.response.data.message);
      } else if (error.request) {
        console.error("No response received:", error.request);
        alert("No response from server.");
      } else {
        console.error("Error setting up request:", error.message);
        alert("An error occurred while setting up the request.");
      }
    }
  };

  return (
    <Card className="mx-auto max-w-sm">
      <CardHeader className="space-y-1">
        <CardTitle className="text-3  xl font-bold">
          Welcome to My Finance
        </CardTitle>
        <CardDescription>
          One Stop Solution to track all Personal Expenses
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Tabs className="space-y-4" defaultValue="signup">
          <TabsList className="grid grid-cols-2 gap-2">
            <TabsTrigger value="signup">Sign Up</TabsTrigger>
            <TabsTrigger value="signin">Sign In</TabsTrigger>
          </TabsList>
          <TabsContent value="signup">
            <div className="space-y-4">
              <div className="space-y-2">
                <div className="space-y-1">
                  <Label htmlFor="email">Username</Label>
                  <Input
                    id="email"
                    placeholder="m@example.com"
                    type="email"
                    value={signupData.email}
                    onChange={handleChangeSignup}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    type="password"
                    value={signupData.password}
                    onChange={handleChangeSignup}
                  />
                </div>
              </div>
              <Button className="w-full" onClick={submitHandlerSignup}>
                Sign Up
              </Button>
            </div>
          </TabsContent>
          <TabsContent value="signin">
            <div className="space-y-2">
              <div className="space-y-1">
                <Label htmlFor="email">Username</Label>
                <Input
                  id="email"
                  placeholder="m@example.com"
                  type="email"
                  value={signinData.email}
                  onChange={handleChangeSignin}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="password">Password</Label>
                <Input
                  id="password"
                  type="password"
                  value={signinData.password}
                  onChange={handleChangeSignin}
                />
              </div>
              <div>
                <Button className="w-full" onClick={submitHandlerSignin}>
                  Sign In
                </Button>
              </div>
            </div>
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}
