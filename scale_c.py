import re

CENTER = 512
SCALE = 1.4  # 40% bigger

def scale_val(v):
    return round(CENTER + SCALE * (float(v) - CENTER), 1)

def scale_coords(text):
    # Replace every number (int or float) in the path/gradient with scaled version
    return re.sub(r'-?\d+\.?\d*', lambda m: str(scale_val(m.group())), text)

# Original C path (used for both shadow and main)
c_path = ("M526.6,698.1 Q487.7,698.1 453.9,685.6 Q420.0,673.0 394.8,649.1 "
          "Q370.2,625.7 356.0,591.2 Q341.8,556.7 341.8,515.4 Q341.8,474.3 "
          "356.1,439.3 Q370.4,404.3 396.3,378.7 Q421.7,353.5 457.3,339.7 "
          "Q493.0,325.9 533.5,325.9 Q562.9,325.9 587.1,333.0 Q611.2,340.1 "
          "627.9,349.0 L643.3,335.3 L665.0,335.3 L667.3,464.3 L645.3,464.3 "
          "Q626.7,405.3 600.5,377.7 Q574.4,350.0 536.8,350.0 Q490.0,350.0 "
          "465.6,391.2 Q441.2,432.4 441.2,506.9 Q441.2,549.0 449.5,578.7 "
          "Q457.8,608.5 471.3,626.7 Q485.7,645.6 505.4,654.4 Q525.1,663.3 "
          "549.0,663.3 Q583.8,663.3 611.1,644.3 Q638.4,625.4 660.8,587.1 "
          "L682.2,600.0 Q671.5,621.9 657.8,639.2 Q644.1,656.5 626.2,669.0 "
          "Q606.5,682.7 582.7,690.4 Q558.9,698.1 526.6,698.1 Z")

scaled_path = scale_coords(c_path)

# Gradient coords
grad = {"startX": 341, "startY": 325, "endX": 682, "endY": 698}
scaled_grad = {k: scale_val(v) for k, v in grad.items()}

print("SCALED PATH:")
print(scaled_path)
print()
print("SCALED GRADIENT:")
for k, v in scaled_grad.items():
    print(f"  {k} = {v}")
